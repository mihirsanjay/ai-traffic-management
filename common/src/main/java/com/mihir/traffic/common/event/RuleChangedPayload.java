package com.mihir.traffic.common.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * What a rule looked like after it changed.
 *
 * <p>One record serves creation, update, and deletion. The three are distinguished by the {@link
 * EventType} in the envelope and by the topic the event arrives on, so three near-identical records
 * would carry no information the consumer does not already have.
 *
 * <p>The payload is deliberately self-describing: it carries the rule's targeting and values rather
 * than only an identifier. A consumer can act on one message without calling back to {@code
 * rule-service}, which is what keeps the services decoupled — a callback would reintroduce exactly
 * the synchronous coupling ADR 0005 chose Kafka to avoid, and would race further updates.
 *
 * @param ruleId stable identity of the rule
 * @param version the rule version this event describes; the values below are that version's, and
 *     they never change once written (ADR 0007)
 * @param service the service the rule throttles
 * @param endpoint the endpoint the rule throttles
 * @param limitValue requests permitted per window, or null for a deletion
 * @param windowSpec the window, e.g. {@code 1m}, or null for a deletion
 * @param changedBy identity of whoever made the change
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RuleChangedPayload(
    UUID ruleId,
    int version,
    String service,
    String endpoint,
    Integer limitValue,
    String windowSpec,
    String changedBy) {}
