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
import com.mihir.traffic.ruleservice.web.dto.RuleResponse;
import com.mihir.traffic.ruleservice.web.dto.UpdateRuleRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt at appending a rule version, in its own transaction.
 *
 * <p>A separate bean rather than a method on {@link RuleService}, and this is not stylistic. Spring
 * applies {@code @Transactional} through a proxy, so a self-invocation inside {@code RuleService}
 * would bypass it entirely and run each attempt as two independent auto-committed statements. The
 * version insert and the pointer move must commit together or not at all - a failure between them
 * would leave a rule pointing at a version that does not exist, breaking the invariant ADR 0007
 * depends on. Calling across bean boundaries is what makes the annotation real.
 *
 * <p>{@code REQUIRES_NEW} because {@link VersionConflictRetrier} retries this: a transaction
 * already marked rollback-only cannot be reused, so every attempt needs a genuinely fresh one.
 */
@Component
public class RuleVersionAppender {

  private final RuleRepository ruleRepository;
  private final RuleVersionRepository ruleVersionRepository;
  private final OutboxWriter outboxWriter;
  private final Clock clock;

  /**
   * Creates the appender.
   *
   * @param ruleRepository persistence for rule identities
   * @param ruleVersionRepository persistence for immutable rule versions
   * @param outboxWriter records the rule event in this same transaction
   * @param clock time source, injected so tests are not dependent on the wall clock
   */
  public RuleVersionAppender(
      RuleRepository ruleRepository,
      RuleVersionRepository ruleVersionRepository,
      OutboxWriter outboxWriter,
      Clock clock) {
    this.ruleRepository = ruleRepository;
    this.ruleVersionRepository = ruleVersionRepository;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
  }

  /**
   * Appends the next version of a rule and moves its pointer, atomically.
   *
   * @param ruleId the rule to update
   * @param request the new values
   * @param updatedBy identity of the author
   * @return the rule at its new version
   * @throws RuleOperationException carrying {@link RuleError.RuleNotFound} if absent or
   *     soft-deleted
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RuleResponse append(UUID ruleId, UpdateRuleRequest request, String updatedBy) {
    return append(ruleId, request, updatedBy, false);
  }

  /**
   * Appends a version and then deliberately fails, so a test can prove the version row does not
   * survive the failure.
   *
   * <p>Package-private and test-only. It exists because the atomicity of these two writes cannot be
   * observed any other way: every ordinary failure happens on the first write, leaving nothing
   * half-written to detect.
   *
   * @param ruleId the rule to update
   * @param request the new values
   * @param updatedBy identity of the author
   * @return never returns normally
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  RuleResponse appendThenFail(UUID ruleId, UpdateRuleRequest request, String updatedBy) {
    return append(ruleId, request, updatedBy, true);
  }

  private RuleResponse append(
      UUID ruleId, UpdateRuleRequest request, String updatedBy, boolean failAfterVersionWrite) {
    Rule rule =
        ruleRepository
            .findLiveById(ruleId)
            .orElseThrow(() -> new RuleOperationException(new RuleError.RuleNotFound(ruleId)));

    RuleVersion current =
        ruleVersionRepository
            .findById(new RuleVersionId(ruleId, rule.getCurrentVersion()))
            // The foreign key and the same-transaction write make this
            // unreachable short of manual data surgery; it is a broken
            // invariant, not a domain outcome, so it is not a RuleError.
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Rule "
                            + ruleId
                            + " points at missing version "
                            + rule.getCurrentVersion()));

    Instant now = clock.instant();
    RuleVersion next = current.next(request.limit(), request.window(), now, updatedBy);

    // Order matters: the version row goes in first, so the pointer is never
    // moved to a version that does not exist. The flush forces both the
    // primary-key check and the @Version check to happen here, where the
    // retrier can see them, rather than at commit time where it could not.
    ruleVersionRepository.saveAndFlush(next);

    if (failAfterVersionWrite) {
      // The version row is now flushed. In a transaction it disappears on
      // rollback; without one it is already committed and orphans the pointer.
      throw new IllegalStateException("deliberate failure after version write");
    }

    rule.applyVersion(next.getVersion());
    ruleRepository.saveAndFlush(rule);

    // The outbox write belongs here rather than in RuleService.update(), and the
    // reason is structural. update() is deliberately not transactional - the
    // retry has to sit outside a transaction boundary - so a write there would
    // land outside any transaction AND run once per retry attempt, emitting an
    // event for every failed try. Here it inherits this method's REQUIRES_NEW,
    // so a losing attempt rolls the event back along with the version row.
    //
    // Placed after the deliberate-failure branch above so appendThenFail keeps
    // testing exactly what it did before.
    outboxWriter.writeRuleEvent(
        EventType.RULE_UPDATED,
        OutboxWriter.payload(
            ruleId,
            next.getVersion(),
            rule.getService(),
            rule.getEndpoint(),
            next.getLimitValue(),
            next.getWindowSpec(),
            updatedBy),
        now,
        TraceContext.currentTraceId());

    return RuleResponse.of(rule, next);
  }
}
