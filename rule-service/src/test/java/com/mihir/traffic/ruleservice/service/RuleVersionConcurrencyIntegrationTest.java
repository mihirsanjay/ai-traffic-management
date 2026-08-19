package com.mihir.traffic.ruleservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mihir.traffic.ruleservice.AbstractIntegrationTest;
import com.mihir.traffic.ruleservice.domain.RuleError;
import com.mihir.traffic.ruleservice.domain.RuleOperationException;
import com.mihir.traffic.ruleservice.domain.RuleVersion;
import com.mihir.traffic.ruleservice.repository.RuleRepository;
import com.mihir.traffic.ruleservice.repository.RuleVersionRepository;
import com.mihir.traffic.ruleservice.web.dto.CreateRuleRequest;
import com.mihir.traffic.ruleservice.web.dto.RuleResponse;
import com.mihir.traffic.ruleservice.web.dto.UpdateRuleRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The test ADR 0008 is not proven without: parallel updates to one rule must never produce a
 * duplicate version number.
 *
 * <p>This runs against a real PostgreSQL rather than a mock, because the guarantee under test is a
 * database guarantee. The composite primary key {@code (rule_id, version)} is what makes duplicates
 * physically impossible; optimistic locking only decides whether the loser of a race gets a clean
 * retry or an ugly error. A mocked repository could not fail in either of the ways that matter.
 *
 * <p>Writers are started against a latch so they collide genuinely, rather than being serialised by
 * the cost of starting each thread.
 */
class RuleVersionConcurrencyIntegrationTest extends AbstractIntegrationTest {

  /** Enough concurrency to force real contention without making the test slow. */
  private static final int WRITERS = 8;

  private static final int AWAIT_SECONDS = 30;

  @Autowired private RuleService ruleService;
  @Autowired private RuleRepository ruleRepository;
  @Autowired private RuleVersionRepository ruleVersionRepository;

  @BeforeEach
  void clearRules() {
    ruleVersionRepository.deleteAll();
    ruleRepository.deleteAll();
  }

  @Test
  void parallelUpdatesNeverDuplicateAVersionNumber() throws Exception {
    RuleResponse rule =
        ruleService.create(new CreateRuleRequest("orders", "/orders", 1000, "1m"), "alice");

    Outcome outcome = updateConcurrently(rule.id(), WRITERS);

    List<RuleVersion> stored = ruleVersionRepository.findHistory(rule.id());
    List<Integer> versions = stored.stream().map(RuleVersion::getVersion).toList();

    // The core claim. Every version number appears exactly once.
    assertThat(versions).doesNotHaveDuplicates();

    // And the sequence has no gaps: version 1 from the create, then one per
    // writer that succeeded. A gap would mean a version was consumed by a
    // writer whose row never landed - a lost update wearing a disguise.
    assertThat(versions)
        .containsExactlyInAnyOrderElementsOf(
            rangeClosed(
                RuleVersion.FIRST_VERSION, RuleVersion.FIRST_VERSION + outcome.succeeded()));
  }

  @Test
  void everySuccessfulUpdateIsRepresentedByExactlyOneStoredVersion() throws Exception {
    RuleResponse rule =
        ruleService.create(new CreateRuleRequest("payments", "/payments", 1000, "1m"), "alice");

    Outcome outcome = updateConcurrently(rule.id(), WRITERS);

    // Not merely "no duplicates": a writer that returned 200 must have left a
    // row behind. Silently losing an acknowledged write is the failure mode
    // optimistic locking exists to prevent, and it would not show up as a
    // duplicate version.
    assertThat(ruleVersionRepository.findHistory(rule.id())).hasSize(outcome.succeeded() + 1);

    assertThat(outcome.succeeded() + outcome.conflicted()).isEqualTo(WRITERS);
  }

  @Test
  void thePointerEndsOnTheHighestVersionThatWasActuallyWritten() throws Exception {
    RuleResponse rule =
        ruleService.create(new CreateRuleRequest("inventory", "/inventory", 1000, "1m"), "alice");

    updateConcurrently(rule.id(), WRITERS);

    int highestStored =
        ruleVersionRepository.findHistory(rule.id()).stream()
            .mapToInt(RuleVersion::getVersion)
            .max()
            .orElseThrow();

    // A pointer left behind the newest row means a caller reads a stale rule;
    // a pointer ahead of it means it references a version that does not exist.
    assertThat(ruleRepository.findLiveById(rule.id()).orElseThrow().getCurrentVersion())
        .isEqualTo(highestStored);
  }

  @Test
  void aLoserOfTheRaceFailsAsAConflictRatherThanAnInternalError() throws Exception {
    RuleResponse rule =
        ruleService.create(new CreateRuleRequest("orders", "/orders-hot", 1000, "1m"), "alice");

    // Heavier contention than the other tests, to make exhausting the retry
    // budget likely rather than incidental.
    Outcome outcome = updateConcurrently(rule.id(), WRITERS * 3);

    // Whatever failed, failed as a domain conflict. Any other exception shape
    // would surface to a caller as a 500, which ADR 0008 explicitly rules out.
    assertThat(outcome.unexpected()).isEmpty();
    assertThat(ruleVersionRepository.findHistory(rule.id()).stream().map(RuleVersion::getVersion))
        .doesNotHaveDuplicates();
  }

  /** Fires {@code writers} updates at one rule simultaneously and classifies what came back. */
  private Outcome updateConcurrently(UUID ruleId, int writers) throws Exception {
    AtomicInteger succeeded = new AtomicInteger();
    AtomicInteger conflicted = new AtomicInteger();
    List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

    // A latch rather than staggered starts: without it the first writer often
    // commits before the last one has even been scheduled, and the test would
    // pass without ever creating the race it claims to test.
    CountDownLatch startLine = new CountDownLatch(1);

    try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
      List<Future<?>> futures = new ArrayList<>(writers);
      for (int i = 0; i < writers; i++) {
        int limit = 100 + i;
        futures.add(
            pool.submit(
                () -> {
                  startLine.await();
                  try {
                    ruleService.update(ruleId, new UpdateRuleRequest(limit, "1m"), "writer");
                    succeeded.incrementAndGet();
                  } catch (RuleOperationException e) {
                    if (e.getError() instanceof RuleError.VersionConflict) {
                      conflicted.incrementAndGet();
                    } else {
                      unexpected.add(e);
                    }
                  }
                  return null;
                }));
      }

      startLine.countDown();
      for (Future<?> future : futures) {
        try {
          future.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
          // A writer that died in a way the classifier never saw - record it
          // so the assertions can fail with the real cause rather than a count.
          unexpected.add(e.getCause());
        }
      }
    }

    return new Outcome(succeeded.get(), conflicted.get(), unexpected);
  }

  /** What a batch of concurrent writers produced. */
  private record Outcome(int succeeded, int conflicted, List<Throwable> unexpected) {}

  private static List<Integer> rangeClosed(int fromInclusive, int toInclusive) {
    List<Integer> values = new ArrayList<>();
    for (int i = fromInclusive; i <= toInclusive; i++) {
      values.add(i);
    }
    return values;
  }
}
