package com.mihir.traffic.ruleservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Unit coverage of the retry schedule (ADR 0008), with time and sleep stubbed.
 *
 * <p>Nothing here really sleeps: a test that waited out its own backoff would be slow and would
 * assert on the wall clock, which is exactly the flakiness the standards forbid.
 */
class VersionConflictRetrierTest {

  private static final RuntimeException EXHAUSTED = new IllegalStateException("exhausted");

  private final List<Duration> slept = new ArrayList<>();
  private long nanos;

  /** Advances the fake clock by whatever the retrier asks to sleep, and records the request. */
  private VersionConflictRetrier retrier(VersionRetryProperties properties) {
    return new VersionConflictRetrier(
        properties,
        () -> nanos,
        duration -> {
          slept.add(duration);
          nanos += duration.toNanos();
        });
  }

  private static VersionRetryProperties properties(int maxAttempts, Duration maxElapsed) {
    return new VersionRetryProperties(
        maxAttempts, Duration.ofMillis(20), Duration.ofMillis(200), maxElapsed, 0.0);
  }

  @Test
  void uncontendedCallRunsOnceAndNeverSleeps() {
    AtomicInteger calls = new AtomicInteger();

    String result =
        retrier(properties(5, Duration.ofSeconds(2)))
            .call(
                () -> {
                  calls.incrementAndGet();
                  return "ok";
                },
                attempts -> EXHAUSTED);

    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(1);
    assertThat(slept).isEmpty();
  }

  @Test
  void conflictIsRetriedUntilItSucceeds() {
    AtomicInteger calls = new AtomicInteger();

    String result =
        retrier(properties(5, Duration.ofSeconds(2)))
            .call(
                () -> {
                  if (calls.incrementAndGet() < 3) {
                    throw new OptimisticLockingFailureException("lost the race");
                  }
                  return "ok";
                },
                attempts -> EXHAUSTED);

    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(3);
    assertThat(slept).hasSize(2);
  }

  @Test
  void duplicateVersionInsertIsTreatedAsAConflictAndRetried() {
    AtomicInteger calls = new AtomicInteger();

    // The loser of the race can surface either shape depending on which check
    // the database reaches first: the (rule_id, version) primary key, or the
    // @Version pointer update matching zero rows. Both mean "try again".
    String result =
        retrier(properties(5, Duration.ofSeconds(2)))
            .call(
                () -> {
                  if (calls.incrementAndGet() < 2) {
                    throw new DataIntegrityViolationException("duplicate key (rule_id, version)");
                  }
                  return "ok";
                },
                attempts -> EXHAUSTED);

    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(2);
  }

  @Test
  void backoffGrowsExponentiallyAndIsCappedAtMaxBackoff() {
    retrierExhausts(properties(6, Duration.ofMinutes(1)));

    // 20, 40, 80, 160, then capped at 200 rather than continuing to 320.
    assertThat(slept)
        .containsExactly(
            Duration.ofMillis(20),
            Duration.ofMillis(40),
            Duration.ofMillis(80),
            Duration.ofMillis(160),
            Duration.ofMillis(200));
  }

  @Test
  void exhaustedAttemptBudgetThrowsRatherThanMaskingTheFailure() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                retrier(properties(3, Duration.ofSeconds(2)))
                    .call(
                        () -> {
                          calls.incrementAndGet();
                          throw new OptimisticLockingFailureException("lost the race");
                        },
                        attempts -> new IllegalStateException("gave up after " + attempts)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("gave up after 3");

    assertThat(calls).hasValue(3);
  }

  @Test
  void elapsedBudgetEndsRetryEvenWithAttemptsRemaining() {
    // A generous attempt cap with a tight time budget: the elapsed bound must
    // be what stops this, or a slow backoff could stall a caller indefinitely.
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                retrier(properties(50, Duration.ofMillis(100)))
                    .call(
                        () -> {
                          calls.incrementAndGet();
                          throw new OptimisticLockingFailureException("lost the race");
                        },
                        attempts -> EXHAUSTED))
        .isSameAs(EXHAUSTED);

    assertThat(calls.get()).isLessThan(50);
    assertThat(Duration.ofNanos(nanos)).isLessThanOrEqualTo(Duration.ofMillis(100));
  }

  @Test
  void jitterOnlyEverShortensABackoffNeverLengthensIt() {
    VersionRetryProperties jittered =
        new VersionRetryProperties(
            8, Duration.ofMillis(20), Duration.ofMillis(200), Duration.ofMinutes(1), 0.5);

    retrierExhausts(jittered);

    // Un-jittered schedule is 20, 40, 80, 160, 200, 200, 200. With a 0.5
    // factor each delay lands between half and full - never above, or
    // competing writers would drift apart more slowly than the cap promises.
    List<Duration> ceiling =
        List.of(
            Duration.ofMillis(20),
            Duration.ofMillis(40),
            Duration.ofMillis(80),
            Duration.ofMillis(160),
            Duration.ofMillis(200),
            Duration.ofMillis(200),
            Duration.ofMillis(200));

    assertThat(slept).hasSize(ceiling.size());
    for (int i = 0; i < slept.size(); i++) {
      assertThat(slept.get(i)).isBetween(ceiling.get(i).dividedBy(2), ceiling.get(i));
    }
  }

  @Test
  void interruptedRetryStopsImmediatelyAndRestoresTheInterruptFlag() {
    VersionConflictRetrier interrupting =
        new VersionConflictRetrier(
            properties(5, Duration.ofSeconds(2)),
            () -> nanos,
            duration -> {
              throw new InterruptedException("cancelled");
            });

    try {
      assertThatThrownBy(
              () ->
                  interrupting.call(
                      () -> {
                        throw new OptimisticLockingFailureException("lost the race");
                      },
                      attempts -> EXHAUSTED))
          .isSameAs(EXHAUSTED);

      // Swallowing the interrupt would leave the caller unable to cancel this
      // thread ever again.
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      // Clear it so the flag does not leak into whatever test runs next.
      Thread.interrupted();
    }
  }

  private void retrierExhausts(VersionRetryProperties properties) {
    // Exhaustion is the point: the assertions that follow inspect the backoff
    // schedule this produced, not the exception it ended with.
    assertThatThrownBy(
            () ->
                retrier(properties)
                    .call(
                        () -> {
                          throw new OptimisticLockingFailureException("lost the race");
                        },
                        attempts -> EXHAUSTED))
        .isSameAs(EXHAUSTED);
  }
}
