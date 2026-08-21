package com.mihir.traffic.ruleservice.service;

import com.mihir.traffic.common.event.EventType;
import com.mihir.traffic.ruleservice.domain.Rule;
import com.mihir.traffic.ruleservice.domain.RuleError;
import com.mihir.traffic.ruleservice.domain.RuleOperationException;
import com.mihir.traffic.ruleservice.domain.RuleVersion;
import com.mihir.traffic.ruleservice.domain.RuleVersionId;
import com.mihir.traffic.ruleservice.observability.TraceContext;
import com.mihir.traffic.ruleservice.outbox.OutboxWriter;
import com.mihir.traffic.ruleservice.repository.RuleRepository;
import com.mihir.traffic.ruleservice.repository.RuleVersionRepository;
import com.mihir.traffic.ruleservice.web.dto.CreateRuleRequest;
import com.mihir.traffic.ruleservice.web.dto.RulePage;
import com.mihir.traffic.ruleservice.web.dto.RuleResponse;
import com.mihir.traffic.ruleservice.web.dto.RuleVersionResponse;
import com.mihir.traffic.ruleservice.web.dto.UpdateRuleRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule lifecycle: creation, retrieval, listing, and soft deletion.
 *
 * <p>Transaction boundaries live here rather than in the controller, and no remote call is made
 * inside one.
 *
 * <p>Updating appends an immutable version and moves the rule's pointer (ADR 0007), guarded by
 * optimistic locking with bounded, jittered retry (ADR 0008). The retry lives outside the
 * transaction boundary, in {@link VersionConflictRetrier}, because a failed transaction cannot be
 * reused.
 */
@Service
public class RuleService {

  /** Ceiling on page size. An unbounded list endpoint is an outage waiting for data growth. */
  public static final int MAX_PAGE_SIZE = 100;

  /** Page size used when a caller does not ask for one. */
  public static final int DEFAULT_PAGE_SIZE = 20;

  private final RuleRepository ruleRepository;
  private final RuleVersionRepository ruleVersionRepository;
  private final RuleVersionAppender versionAppender;
  private final VersionConflictRetrier retrier;
  private final OutboxWriter outboxWriter;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param ruleRepository persistence for rule identities
   * @param ruleVersionRepository persistence for immutable rule versions
   * @param versionAppender transactional append-and-move-pointer step
   * @param retrier bounded retry for optimistic-locking conflicts
   * @param outboxWriter records rule events in the same transaction as the change
   * @param clock time source, injected so tests are not dependent on the wall clock
   */
  public RuleService(
      RuleRepository ruleRepository,
      RuleVersionRepository ruleVersionRepository,
      RuleVersionAppender versionAppender,
      VersionConflictRetrier retrier,
      OutboxWriter outboxWriter,
      Clock clock) {
    this.ruleRepository = ruleRepository;
    this.ruleVersionRepository = ruleVersionRepository;
    this.versionAppender = versionAppender;
    this.retrier = retrier;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
  }

  /**
   * Creates a rule at version 1.
   *
   * <p>The identity row and its first version are written in one transaction, so a rule can never
   * exist while pointing at a version that does not.
   *
   * @param request the validated rule to create
   * @param createdBy identity of the author
   * @return the created rule
   * @throws RuleOperationException carrying {@link RuleError.DuplicateRule} if a live rule already
   *     targets the same service and endpoint
   */
  @Transactional
  public RuleResponse create(CreateRuleRequest request, String createdBy) {
    if (ruleRepository.existsLiveByServiceAndEndpoint(request.service(), request.endpoint())) {
      throw new RuleOperationException(
          new RuleError.DuplicateRule(request.service(), request.endpoint()));
    }

    Instant now = clock.instant();
    Rule rule = Rule.create(request.service(), request.endpoint(), now, createdBy);
    RuleVersion version =
        RuleVersion.first(rule.getRuleId(), request.limit(), request.window(), now, createdBy);

    try {
      ruleRepository.saveAndFlush(rule);
      ruleVersionRepository.saveAndFlush(version);
    } catch (DataIntegrityViolationException e) {
      // The partial unique index is the real guarantee; the check above only
      // makes the common case a clean 409. Losing the race still lands here.
      throw new RuleOperationException(
          new RuleError.DuplicateRule(request.service(), request.endpoint()));
    }

    // Outside the try deliberately, not inside it. An outbox failure surfaces as
    // a DataIntegrityViolationException too, and inside the block above it would
    // be caught and reported to the caller as a 409 duplicate rule - a confident
    // wrong answer about an entirely different table. Out here it propagates as
    // a 500, which is the honest one. Still the same transaction, so the event
    // and the rule remain atomic.
    outboxWriter.writeRuleEvent(
        EventType.RULE_CREATED,
        OutboxWriter.payload(
            rule.getRuleId(),
            version.getVersion(),
            rule.getService(),
            rule.getEndpoint(),
            version.getLimitValue(),
            version.getWindowSpec(),
            createdBy),
        now,
        TraceContext.currentTraceId());

    return RuleResponse.of(rule, version);
  }

  /**
   * Retrieves a live rule with the values of its current version.
   *
   * @param ruleId the rule to retrieve
   * @return the rule
   * @throws RuleOperationException carrying {@link RuleError.RuleNotFound} if absent or
   *     soft-deleted
   */
  @Transactional(readOnly = true)
  public RuleResponse get(UUID ruleId) {
    Rule rule =
        ruleRepository
            .findLiveById(ruleId)
            .orElseThrow(() -> new RuleOperationException(new RuleError.RuleNotFound(ruleId)));
    return RuleResponse.of(rule, requireCurrentVersion(rule));
  }

  /**
   * Lists live rules, cursor-paginated.
   *
   * @param cursor opaque cursor from a previous page, or null to start at the beginning
   * @param requestedSize desired page size; clamped to {@link #MAX_PAGE_SIZE}
   * @return a page of rules and the cursor for the next one
   * @throws RuleOperationException carrying {@link RuleError.MalformedCursor} for an undecodable
   *     cursor
   */
  @Transactional(readOnly = true)
  public RulePage list(String cursor, Integer requestedSize) {
    int size = clampPageSize(requestedSize);

    // Fetch one extra row to learn whether a further page exists without a
    // second count query.
    Limit limit = Limit.of(size + 1);
    List<Rule> rules;
    if (cursor == null || cursor.isBlank()) {
      rules = ruleRepository.findFirstLivePage(limit);
    } else {
      PageCursor position = PageCursor.decode(cursor);
      rules = ruleRepository.findLivePageAfter(position.createdAt(), position.ruleId(), limit);
    }

    boolean hasMore = rules.size() > size;
    List<Rule> page = hasMore ? rules.subList(0, size) : rules;
    String nextCursor = hasMore ? PageCursor.encode(page.get(page.size() - 1)) : null;

    return new RulePage(toResponses(page), nextCursor);
  }

  /**
   * Updates a rule by appending a new immutable version and moving its pointer.
   *
   * <p>No stored rule value is ever overwritten (ADR 0007). The read-compute-insert sequence is
   * guarded by optimistic locking and retried on conflict (ADR 0008); a caller observes contention
   * as latency, and only as an error once the retry budget is exhausted.
   *
   * <p>Deliberately not {@code @Transactional}. Each attempt needs its own transaction - a failed
   * one cannot be reused - and the retry must therefore sit outside the boundary. The transactional
   * work lives in {@link RuleVersionAppender}, a separate bean so the call actually crosses
   * Spring's proxy; a self-invocation here would silently run without a transaction at all.
   *
   * @param ruleId the rule to update
   * @param request the new values
   * @param updatedBy identity of the author
   * @return the rule at its new version
   * @throws RuleOperationException carrying {@link RuleError.RuleNotFound} if absent or
   *     soft-deleted, or {@link RuleError.VersionConflict} if the retry budget is exhausted
   */
  public RuleResponse update(UUID ruleId, UpdateRuleRequest request, String updatedBy) {
    return retrier.call(
        () -> versionAppender.append(ruleId, request, updatedBy),
        attempts -> new RuleOperationException(new RuleError.VersionConflict(ruleId, attempts)));
  }

  /**
   * Lists every stored version of a rule, newest first.
   *
   * <p>Soft-deleted rules keep their history and remain readable here: the audit trail outliving
   * the rule is the reason deletion is a tombstone at all (ADR 0007).
   *
   * @param ruleId the rule whose history to read
   * @return every version of the rule, newest first
   * @throws RuleOperationException carrying {@link RuleError.RuleNotFound} if no such rule exists
   */
  @Transactional(readOnly = true)
  public List<RuleVersionResponse> listVersions(UUID ruleId) {
    requireRuleExists(ruleId);
    List<RuleVersion> history = ruleVersionRepository.findHistory(ruleId);
    List<RuleVersionResponse> responses = new ArrayList<>(history.size());
    for (RuleVersion version : history) {
      responses.add(RuleVersionResponse.of(version));
    }
    return responses;
  }

  /**
   * Retrieves one specific version of a rule.
   *
   * @param ruleId the owning rule
   * @param version the version number to read
   * @return that version's stored values
   * @throws RuleOperationException carrying {@link RuleError.RuleNotFound} if the rule or the
   *     version does not exist
   */
  @Transactional(readOnly = true)
  public RuleVersionResponse getVersion(UUID ruleId, int version) {
    requireRuleExists(ruleId);
    return ruleVersionRepository
        .findById(new RuleVersionId(ruleId, version))
        .map(RuleVersionResponse::of)
        .orElseThrow(() -> new RuleOperationException(new RuleError.RuleNotFound(ruleId)));
  }

  /**
   * Soft-deletes a rule, leaving the row and its version history intact.
   *
   * <p>Per ADR 0007 deletion is a tombstone: hard deletion would destroy the history that rollback
   * and audit depend on.
   *
   * @param ruleId the rule to delete
   * @param deletedBy identity of whoever is deleting it
   * @throws RuleOperationException carrying {@link RuleError.RuleNotFound} if absent or already
   *     deleted
   */
  @Transactional
  public void softDelete(UUID ruleId, String deletedBy) {
    Rule rule =
        ruleRepository
            .findLiveById(ruleId)
            .orElseThrow(() -> new RuleOperationException(new RuleError.RuleNotFound(ruleId)));

    // Read the current version before the tombstone goes on, so the event can
    // report what the rule was when it died. A consumer removing the rule from
    // the data plane needs its targeting, and after deletion there is nowhere
    // else to get it without a second query.
    RuleVersion current = requireCurrentVersion(rule);

    Instant now = clock.instant();
    rule.softDelete(now, deletedBy);
    ruleRepository.save(rule);

    // Limit and window are null: the rule no longer has any. The identity and
    // targeting are what a consumer needs in order to stop enforcing it.
    outboxWriter.writeRuleEvent(
        EventType.RULE_DELETED,
        OutboxWriter.payload(
            rule.getRuleId(),
            current.getVersion(),
            rule.getService(),
            rule.getEndpoint(),
            null,
            null,
            deletedBy),
        now,
        TraceContext.currentTraceId());
  }

  private List<RuleResponse> toResponses(List<Rule> rules) {
    if (rules.isEmpty()) {
      return List.of();
    }

    // One batched lookup rather than one query per rule.
    List<RuleVersionId> ids = new ArrayList<>(rules.size());
    for (Rule rule : rules) {
      ids.add(new RuleVersionId(rule.getRuleId(), rule.getCurrentVersion()));
    }

    Map<RuleVersionId, RuleVersion> versions = new HashMap<>();
    for (RuleVersion version : ruleVersionRepository.findAllByIdIn(ids)) {
      versions.put(version.getId(), version);
    }

    List<RuleResponse> responses = new ArrayList<>(rules.size());
    for (Rule rule : rules) {
      RuleVersion version =
          versions.get(new RuleVersionId(rule.getRuleId(), rule.getCurrentVersion()));
      if (version == null) {
        throw new IllegalStateException(
            "Rule " + rule.getRuleId() + " points at missing version " + rule.getCurrentVersion());
      }
      responses.add(RuleResponse.of(rule, version));
    }
    return responses;
  }

  /**
   * Confirms a rule exists at all, including soft-deleted ones, so a history request for a
   * genuinely unknown id is a 404 rather than an empty list.
   */
  private void requireRuleExists(UUID ruleId) {
    if (!ruleRepository.existsById(ruleId)) {
      throw new RuleOperationException(new RuleError.RuleNotFound(ruleId));
    }
  }

  private RuleVersion requireCurrentVersion(Rule rule) {
    RuleVersionId id = new RuleVersionId(rule.getRuleId(), rule.getCurrentVersion());
    return ruleVersionRepository
        .findById(id)
        // The foreign key and the same-transaction write make this unreachable
        // short of manual data surgery; it is a broken invariant, not a domain
        // outcome, so it is not a RuleError.
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Rule "
                        + rule.getRuleId()
                        + " points at missing version "
                        + rule.getCurrentVersion()));
  }

  private static int clampPageSize(Integer requestedSize) {
    if (requestedSize == null) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.clamp(requestedSize, 1, MAX_PAGE_SIZE);
  }
}
