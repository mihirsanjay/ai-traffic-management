package com.mihir.traffic.ruleservice.outbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tuning for the outbox publisher.
 *
 * @param enabled whether the scheduled poll does any work; false in tests, which drive the
 *     publisher directly rather than waiting on a scheduler
 * @param pollInterval delay between the end of one poll and the start of the next
 * @param batchSize maximum events claimed per poll
 * @param sendTimeout how long to wait for the broker to acknowledge one event before treating it as
 *     failed and leaving it for the next poll
 */
@Validated
@ConfigurationProperties(prefix = "rule-service.outbox")
public record OutboxProperties(
    boolean enabled,
    @NotNull Duration pollInterval,
    @Min(1) @Max(1000) int batchSize,
    @NotNull Duration sendTimeout) {

  /** Applies defaults so a minimal configuration block still yields a working publisher. */
  public OutboxProperties {
    pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
    batchSize = batchSize == 0 ? 100 : batchSize;
    sendTimeout = sendTimeout == null ? Duration.ofSeconds(5) : sendTimeout;
  }
}
