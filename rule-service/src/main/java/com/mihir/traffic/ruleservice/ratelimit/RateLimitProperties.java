package com.mihir.traffic.ruleservice.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the rate limit guarding this service's own API.
 *
 * <p>Bound and validated at startup, so a nonsensical limit fails the application rather than
 * silently throttling every caller to zero.
 *
 * @param enabled whether the filter rejects over-limit requests; disabled in tests that are not
 *     about throttling
 * @param capacity tokens a single caller may spend within {@code refillPeriod}
 * @param refillPeriod window over which a full {@code capacity} of tokens is restored
 * @param redisTimeout ceiling on a single Redis round trip; a limiter that waits indefinitely on
 *     its counter store turns a slow dependency into an outage of the API it protects
 */
@Validated
@ConfigurationProperties(prefix = "rule-service.rate-limit")
public record RateLimitProperties(
    boolean enabled,
    @Min(1) int capacity,
    @NotNull Duration refillPeriod,
    @NotNull Duration redisTimeout) {

  /**
   * Applies defaults for any property left unset.
   *
   * <p>The defaults are deliberately generous: this limiter exists to stop a runaway client from
   * overwhelming the control plane, not to ration normal use.
   */
  public RateLimitProperties {
    if (capacity == 0) {
      capacity = 100;
    }
    if (refillPeriod == null) {
      refillPeriod = Duration.ofMinutes(1);
    }
    if (redisTimeout == null) {
      redisTimeout = Duration.ofSeconds(1);
    }
  }
}
