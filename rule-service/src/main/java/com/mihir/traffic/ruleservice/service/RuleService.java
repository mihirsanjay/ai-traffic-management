package com.mihir.traffic.ruleservice.service;

import com.mihir.traffic.ruleservice.domain.Rule;
import com.mihir.traffic.ruleservice.domain.RuleError;
import com.mihir.traffic.ruleservice.domain.RuleOperationException;
import com.mihir.traffic.ruleservice.domain.RuleVersion;
import com.mihir.traffic.ruleservice.domain.RuleVersionId;
import com.mihir.traffic.ruleservice.repository.RuleRepository;
import com.mihir.traffic.ruleservice.repository.RuleVersionRepository;
import com.mihir.traffic.ruleservice.web.dto.CreateRuleRequest;
import com.mihir.traffic.ruleservice.web.dto.RulePage;
import com.mihir.traffic.ruleservice.web.dto.RuleResponse;
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
 * <p>Update and version increment are deliberately absent: they arrive with the versioning branch,
 * together with the concurrency test that proves parallel updates cannot duplicate a version
 * number.
 */
@Service
public class RuleService {

  /** Ceiling on page size. An unbounded list endpoint is an outage waiting for data growth. */
  public static final int MAX_PAGE_SIZE = 100;

  /** Page size used when a caller does not ask for one. */
  public static final int DEFAULT_PAGE_SIZE = 20;

  private final RuleRepository ruleRepository;
  private final RuleVersionRepository ruleVersionRepository;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param ruleRepository persistence for rule identities
   * @param ruleVersionRepository persistence for immutable rule versions
   * @param clock time source, injected so tests are not dependent on the wall clock
   */
  public RuleService(
      RuleRepository ruleRepository, RuleVersionRepository ruleVersionRepository, Clock clock) {
    this.ruleRepository = ruleRepository;
    this.ruleVersionRepository = ruleVersionRepository;
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
   * Soft-deletes a rule, leaving the row and its version history intact.
   *
   * <p>Per ADR 0007 deletion is a tombstone: hard deletion would destroy the history that rollback
   * and audit depend on.
   *
   * @param ruleId the rule to delete
   * @throws RuleOperationException carrying {@link RuleError.RuleNotFound} if absent or already
   *     deleted
   */
  @Transactional
  public void softDelete(UUID ruleId) {
    Rule rule =
        ruleRepository
            .findLiveById(ruleId)
            .orElseThrow(() -> new RuleOperationException(new RuleError.RuleNotFound(ruleId)));
    rule.softDelete(clock.instant());
    ruleRepository.save(rule);
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
