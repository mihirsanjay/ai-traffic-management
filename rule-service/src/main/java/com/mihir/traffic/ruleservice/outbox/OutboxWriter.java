package com.mihir.traffic.ruleservice.outbox;

import com.mihir.traffic.common.event.EventEnvelope;
import com.mihir.traffic.common.event.EventType;
import com.mihir.traffic.common.event.RuleChangedPayload;
import com.mihir.traffic.common.event.Topics;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes an event to the outbox inside the caller's transaction.
 *
 * <p>{@code Propagation.MANDATORY} is the load-bearing choice here, and it is worth being explicit
 * about why. This must join the transaction that is making the state change, so the event and the
 * change commit together or not at all — that single property is the entire reason the outbox
 * pattern exists. {@code REQUIRES_NEW} would commit the event separately and defeat it; the default
 * {@code REQUIRED} would silently start a new transaction if called with none active, writing an
 * event for work that may never commit. {@code MANDATORY} throws instead, turning a wrong call site
 * into an immediate, loud failure rather than a rare and baffling one.
 */
@Component
public class OutboxWriter {

  private final OutboxEventRepository repository;
  private final ObjectMapper objectMapper;

  /**
   * Creates the writer.
   *
   * @param repository persistence for outbox rows
   * @param objectMapper serializer for the event envelope
   */
  public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  /**
   * Records a rule event for publication.
   *
   * <p>Serialization happens here rather than in the publisher so that what is stored is exactly
   * what will be sent. The bytes an operator inspects in the outbox row are the bytes that reach
   * the topic.
   *
   * @param eventType what happened
   * @param payload the rule's state after the change
   * @param occurredAt when it happened; passed in so the event carries the same instant as the
   *     state change rather than a slightly later reading of the clock
   * @param traceId correlation id from the originating request, or null
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void writeRuleEvent(
      EventType eventType, RuleChangedPayload payload, Instant occurredAt, String traceId) {
    EventEnvelope<RuleChangedPayload> envelope =
        EventEnvelope.of(eventType, occurredAt, traceId, payload);

    repository.save(
        OutboxEvent.pending(
            envelope.eventId(),
            payload.ruleId(),
            Topics.forRuleEvent(eventType),
            eventType,
            objectMapper.writeValueAsString(envelope),
            traceId,
            occurredAt));
  }

  /**
   * Builds a payload from the values a rule event needs to carry.
   *
   * @param ruleId the rule
   * @param version the version being described
   * @param service the throttled service
   * @param endpoint the throttled endpoint
   * @param limitValue requests per window, or null for a deletion
   * @param windowSpec the window, or null for a deletion
   * @param changedBy who caused the change
   * @return the payload
   */
  public static RuleChangedPayload payload(
      UUID ruleId,
      int version,
      String service,
      String endpoint,
      Integer limitValue,
      String windowSpec,
      String changedBy) {
    return new RuleChangedPayload(
        ruleId, version, service, endpoint, limitValue, windowSpec, changedBy);
  }
}
