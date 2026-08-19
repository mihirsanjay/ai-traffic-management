package com.mihir.traffic.ruleservice.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds on the optimistic-locking retry for rule version increment (ADR 0008).
 *
 * <p>Both bounds exist because either alone is insufficient. An attempt cap alone still permits a
 * long stall if each backoff is slow; an elapsed-time cap alone permits unbounded attempts inside a
 * short window. Whichever is reached first ends the retry, and the caller gets a 409.
 *
 * @param maxAttempts total attempts including the first, so 1 disables retry entirely
 * @param initialBackoff delay before the second attempt, doubled on each subsequent one
 * @param maxBackoff ceiling on a single backoff, so exponential growth cannot run away
 * @param maxElapsed total time budget across all attempts
 * @param jitterFactor fraction of each backoff randomised, in [0, 1]
 */
@Validated
@ConfigurationProperties(prefix = "rule-service.version-retry")
public record VersionRetryProperties(
    @Min(1) @Max(20) int maxAttempts,
    @NotNull Duration initialBackoff,
    @NotNull Duration maxBackoff,
    @NotNull Duration maxElapsed,
    @Min(0) @Max(1) double jitterFactor) {}
