package com.mihir.traffic.common.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * The result of applying a rule version to the data plane.
 *
 * <p>Published to {@code control.deployment.succeeded} and {@code control.deployment.failed}, which
 * currently have no consumer. That is deliberate — see ADR 0009. A producer that publishes whether
 * or not anyone is listening is the property that lets a consumer be added later without touching
 * the producer, and publishing these events is what keeps that property demonstrable rather than
 * merely claimed.
 *
 * @param deploymentId identity of the deployment attempt
 * @param ruleId the rule that was deployed
 * @param version the rule version that was deployed
 * @param status the outcome, matching the deployment service's status vocabulary
 * @param detail human-readable context, particularly why a failure failed; null on success
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeploymentOutcomePayload(
    UUID deploymentId, UUID ruleId, int version, String status, String detail) {}
