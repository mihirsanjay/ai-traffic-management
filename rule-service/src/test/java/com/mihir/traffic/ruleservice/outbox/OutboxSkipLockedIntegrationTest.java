package com.mihir.traffic.ruleservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.mihir.traffic.common.event.EventType;
import com.mihir.traffic.ruleservice.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves two publishers never claim the same event.
 *
 * <p>This is what {@code FOR UPDATE SKIP LOCKED} buys, and it is the reason {@code rule-service}
 * can run more than one replica while every other service in the platform is pinned to one. Without
 * it the second poller would block on the first's locks and then re-read the very same rows, so
 * every event would be published twice by construction rather than only under a crash.
 *
 * <p>The claim only holds its locks inside a transaction, so this exercises {@link
 * OutboxBatchClaimer} — the bean whose whole purpose is to supply that transaction across Spring's
 * proxy.
 */
class OutboxSkipLockedIntegrationTest extends AbstractIntegrationTest {

  private static final int EVENT_COUNT = 20;
  private static final int BATCH_SIZE = 10;

  @Autowired private OutboxEventRepository outboxRepository;
  @Autowired private OutboxBatchClaimer claimer;

  @BeforeEach
  void seedOutbox() {
    outboxRepository.deleteAll();
    for (int i = 0; i < EVENT_COUNT; i++) {
      UUID eventId = UUID.randomUUID();
      outboxRepository.save(
          OutboxEvent.pending(
              eventId,
              UUID.randomUUID(),
              "control.rule.created",
              EventType.RULE_CREATED,
              "{\"eventId\":\"" + eventId + "\"}",
              null,
              Instant.now()));
    }
  }

  @Test
  @DisplayName("two concurrent pollers claim disjoint batches")
  void twoConcurrentPollersClaimDisjointBatches() throws Exception {
    // Released together so both queries genuinely contend, rather than one
    // finishing before the other starts and the test proving nothing.
    CountDownLatch start = new CountDownLatch(1);

    CompletableFuture<List<UUID>> first = claimAfter(start);
    CompletableFuture<List<UUID>> second = claimAfter(start);

    start.countDown();

    List<UUID> firstBatch = first.get(30, TimeUnit.SECONDS);
    List<UUID> secondBatch = second.get(30, TimeUnit.SECONDS);

    Set<UUID> overlap =
        firstBatch.stream().filter(secondBatch::contains).collect(Collectors.toSet());
    assertThat(overlap)
        .as("an event claimed by both pollers would be published twice every single time")
        .isEmpty();

    // Both pollers found work: with SKIP LOCKED the second skips the locked rows
    // and takes the next ones, rather than waiting and finding nothing.
    assertThat(firstBatch).hasSize(BATCH_SIZE);
    assertThat(secondBatch).hasSize(BATCH_SIZE);
  }

  private CompletableFuture<List<UUID>> claimAfter(CountDownLatch start) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            start.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
          return claimer.claim(BATCH_SIZE).stream().map(OutboxEvent::getEventId).toList();
        });
  }
}
