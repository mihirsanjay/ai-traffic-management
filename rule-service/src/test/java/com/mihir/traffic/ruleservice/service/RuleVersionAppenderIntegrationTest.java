package com.mihir.traffic.ruleservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mihir.traffic.ruleservice.AbstractIntegrationTest;
import com.mihir.traffic.ruleservice.repository.RuleRepository;
import com.mihir.traffic.ruleservice.repository.RuleVersionRepository;
import com.mihir.traffic.ruleservice.web.dto.CreateRuleRequest;
import com.mihir.traffic.ruleservice.web.dto.RuleResponse;
import com.mihir.traffic.ruleservice.web.dto.UpdateRuleRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Proves the version append actually runs inside a transaction.
 *
 * <p>This exists because it did not, and nothing caught it. {@code @Transactional} was originally
 * placed on a package-private method of {@link RuleService} that {@code update()} called on {@code
 * this} - a self-invocation, which never crosses Spring's proxy, so the annotation was inert and
 * the version insert and pointer move committed as two independent statements. Every functional
 * test still passed, because {@code saveAndFlush} surfaces constraint violations eagerly either
 * way. Only a crash between the two writes would have revealed it, in production, as a rule
 * permanently pointing at a version that does not exist.
 *
 * <p>The behavioural assertion is {@link #aFailedAppendLeavesNoHalfWrittenVersionBehind}: it forces
 * a failure after the version row is written and shows the row is gone afterwards. Without a
 * transaction that row survives, orphaned.
 */
class RuleVersionAppenderIntegrationTest extends AbstractIntegrationTest {

  @Autowired private RuleService ruleService;
  @Autowired private RuleVersionAppender versionAppender;
  @Autowired private RuleRepository ruleRepository;
  @Autowired private RuleVersionRepository ruleVersionRepository;

  @BeforeEach
  void clearRules() {
    ruleVersionRepository.deleteAll();
    ruleRepository.deleteAll();
  }

  @Test
  void aFailedAppendLeavesNoHalfWrittenVersionBehind() {
    RuleResponse created =
        ruleService.create(new CreateRuleRequest("orders", "/orders", 1000, "1m"), "alice");

    // Force the failure to land *between* the two writes: the version row is
    // inserted, then the pointer move is made to fail. That is the only
    // interleaving that distinguishes a real transaction from two independent
    // auto-committed statements - which is exactly what the self-invocation
    // bug produced.
    assertThatThrownBy(
            () ->
                versionAppender.appendThenFail(
                    created.id(), new UpdateRuleRequest(500, "30s"), "bob"))
        .isInstanceOf(RuntimeException.class);

    // Version 2 must not survive: the whole attempt rolled back. Without a
    // transaction the flushed row is already committed and stays behind,
    // orphaned, with the pointer still naming version 1.
    assertThat(ruleVersionRepository.findHistory(created.id()))
        .as("a failed append must roll back entirely, leaving no orphaned version row")
        .hasSize(1);

    int pointer = ruleRepository.findLiveById(created.id()).orElseThrow().getCurrentVersion();
    assertThat(pointer).isEqualTo(1);
  }

  @Test
  void bothWritesLandTogetherSoThePointerNeverOutrunsStoredHistory() {
    RuleResponse created =
        ruleService.create(new CreateRuleRequest("payments", "/payments", 1000, "1m"), "alice");

    ruleService.update(created.id(), new UpdateRuleRequest(500, "30s"), "bob");

    // The invariant the transaction protects: whatever the pointer names must
    // exist in stored history.
    int pointer = ruleRepository.findLiveById(created.id()).orElseThrow().getCurrentVersion();

    assertThat(ruleVersionRepository.findHistory(created.id()))
        .extracting(version -> version.getVersion())
        .contains(pointer);
  }

  @Test
  void theAppenderIsTransactionallyProxied() {
    // The structural guard. A self-invocation bug reappears the moment someone
    // folds this back into RuleService, and the symptom is silent data loss
    // rather than a failing assertion - so the requirement is named here.
    assertThat(AopUtils.isAopProxy(versionAppender))
        .as("RuleVersionAppender must be proxied, or its @Transactional does nothing")
        .isTrue();

    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
        .as("the test must not supply the transaction itself, or it proves nothing")
        .isFalse();
  }

  @Test
  void appendingToAnUnknownRuleFailsWithoutWritingAnything() {
    UUID missing = UUID.randomUUID();

    assertThatThrownBy(
            () -> versionAppender.append(missing, new UpdateRuleRequest(500, "30s"), "bob"))
        .isInstanceOf(RuntimeException.class);

    assertThat(ruleVersionRepository.findHistory(missing)).isEmpty();
  }
}
