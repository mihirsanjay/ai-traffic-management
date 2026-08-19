package com.mihir.traffic.ruleservice.service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Runs an operation, retrying it when a concurrent writer wins the race for the next version number
 * (ADR 0008).
 *
 * <p>Separate from {@link RuleService} for one structural reason: the retried operation must run in
 * its own transaction, and a transaction that has already failed cannot be reused. Retrying inside
 * the transaction boundary would re-run the work against a rolled-back state and fail identically
 * every time. The retry therefore sits <em>outside</em> the boundary, and each attempt calls back
 * into a fresh {@code @Transactional} method.
 *
 * <p>Backoff is exponential with jitter and bounded on both attempts and elapsed time - unbounded
 * retry turns contention into an outage, and un-jittered retry synchronises competing writers into
 * a thundering herd.
 */
@Component
public class VersionConflictRetrier {

  private static final Logger LOG = LoggerFactory.getLogger(VersionConflictRetrier.class);

  private final VersionRetryProperties properties;
  private final LongSupplier nanoTime;
  private final Sleeper sleeper;

  /**
   * Creates the retrier for production use, backed by the wall clock and a real sleep.
   *
   * <p>Annotated explicitly because this class has two constructors: without it Spring cannot tell
   * which to use, and the test constructor below is not injectable.
   *
   * @param properties the retry bounds
   */
  @Autowired
  public VersionConflictRetrier(VersionRetryProperties properties) {
    this(properties, System::nanoTime, duration -> Thread.sleep(duration.toMillis()));
  }

  /**
   * Creates the retrier with an injectable clock and sleep, so tests can exercise the backoff
   * schedule without actually waiting.
   *
   * @param properties the retry bounds
   * @param nanoTime monotonic time source
   * @param sleeper how a backoff delay is waited out
   */
  VersionConflictRetrier(
      VersionRetryProperties properties, LongSupplier nanoTime, Sleeper sleeper) {
    this.properties = properties;
    this.nanoTime = nanoTime;
    this.sleeper = sleeper;
  }

  /** How a backoff delay is waited out. Injectable so tests need not really sleep. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  /**
   * Runs {@code operation}, retrying it while it loses the optimistic-locking race.
   *
   * @param <T> the operation's result type
   * @param operation the work to run; must be safe to run more than once
   * @param onExhausted supplies the exception thrown when the retry budget runs out, given the
   *     number of attempts made
   * @return the operation's result
   */
  public <T> T call(
      Supplier<T> operation, java.util.function.IntFunction<RuntimeException> onExhausted) {
    long startedAt = nanoTime.getAsLong();
    Duration backoff = properties.initialBackoff();

    for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
      try {
        return operation.get();
      } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
        // Both shapes mean the same thing here: another writer got to this
        // rule first. Optimistic locking surfaces as ConcurrencyFailure when
        // the pointer update matches zero rows, and as DataIntegrityViolation
        // when the loser's INSERT hits the (rule_id, version) primary key
        // first. Neither is an error worth logging above DEBUG - a retry is
        // the designed response, not a degradation.
        if (attempt == properties.maxAttempts()
            || elapsed(startedAt).compareTo(properties.maxElapsed()) >= 0) {
          // WARN, not ERROR: the caller gets a clean 409 and can retry. A
          // human only needs to act if this becomes frequent.
          LOG.warn(
              "Version increment abandoned after {} attempt(s) in {}ms",
              attempt,
              elapsed(startedAt).toMillis());
          throw onExhausted.apply(attempt);
        }
        LOG.debug("Version increment attempt {} lost the race, retrying", attempt);
        backoff = sleepThenGrow(backoff, startedAt, attempt, onExhausted);
      }
    }

    // Unreachable: the loop either returns or throws. Present because the
    // compiler cannot see that maxAttempts is validated to be at least 1.
    throw onExhausted.apply(properties.maxAttempts());
  }

  private Duration sleepThenGrow(
      Duration backoff,
      long startedAt,
      int attempt,
      java.util.function.IntFunction<RuntimeException> onExhausted) {
    Duration remaining = properties.maxElapsed().minus(elapsed(startedAt));
    Duration delay = jitter(backoff);
    if (delay.compareTo(remaining) > 0) {
      delay = remaining;
    }

    if (delay.isPositive()) {
      try {
        sleeper.sleep(delay);
      } catch (InterruptedException e) {
        // Never swallow an interrupt: restore the flag so the caller's own
        // cancellation still works, and stop retrying immediately.
        Thread.currentThread().interrupt();
        throw onExhausted.apply(attempt);
      }
    }

    Duration grown = backoff.multipliedBy(2);
    return grown.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : grown;
  }

  /**
   * Randomises a backoff downward by up to {@code jitterFactor}, so competing writers do not all
   * wake at the same instant and collide again.
   */
  private Duration jitter(Duration backoff) {
    if (properties.jitterFactor() <= 0) {
      return backoff;
    }
    long nanos = backoff.toNanos();
    long jitterRange = (long) (nanos * properties.jitterFactor());
    if (jitterRange <= 0) {
      return backoff;
    }
    return Duration.ofNanos(nanos - ThreadLocalRandom.current().nextLong(jitterRange + 1));
  }

  private Duration elapsed(long startedAt) {
    return Duration.ofNanos(nanoTime.getAsLong() - startedAt);
  }
}
