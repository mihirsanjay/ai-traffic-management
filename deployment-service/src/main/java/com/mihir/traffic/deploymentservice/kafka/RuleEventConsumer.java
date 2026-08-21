package com.mihir.traffic.deploymentservice.kafka;

import com.mihir.traffic.common.PlatformConstants;
import com.mihir.traffic.common.event.EventEnvelope;
import com.mihir.traffic.common.event.RuleChangedPayload;
import com.mihir.traffic.common.event.Topics;
import com.mihir.traffic.deploymentservice.service.DeploymentService;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns rule events into deployments.
 *
 * <p>One listener across all three rule topics rather than three near-identical ones: they share
 * deserialization, idempotency, and target table, and the payload is the same shape for all of
 * them.
 *
 * <p>The consumer group is what makes this service an independent reader of the stream. Another
 * service added later reads the same events under its own group, from its own offsets, with no
 * change to the producer — the property ADR 0005 chose Kafka for and ADR 0009 kept.
 *
 * <p>Acknowledgement is manual and happens after the work commits. Auto-commit acknowledges on a
 * timer whether or not processing succeeded, which loses events when a consumer dies mid-batch;
 * committing afterwards means a crash redelivers rather than drops, and redelivery is safe because
 * the deployment service keys on the event id.
 */
@Component
public class RuleEventConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(RuleEventConsumer.class);

  /** MDC key for the correlation id, matching what rule-service logs under. */
  private static final String TRACE_ID_KEY = "traceId";

  private final DeploymentService deploymentService;
  private final ObjectMapper objectMapper;

  /**
   * Creates the consumer.
   *
   * @param deploymentService records the deployment
   * @param objectMapper deserializer for the event envelope
   */
  public RuleEventConsumer(DeploymentService deploymentService, ObjectMapper objectMapper) {
    this.deploymentService = deploymentService;
    this.objectMapper = objectMapper;
  }

  /**
   * Handles one rule event.
   *
   * @param record the raw record, needed for its headers and topic
   * @param acknowledgment commits the offset once the work is durable
   */
  @KafkaListener(
      topics = {Topics.RULE_CREATED, Topics.RULE_UPDATED, Topics.RULE_DELETED},
      groupId = "${spring.kafka.consumer.group-id}")
  public void onRuleEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    String traceId = headerValue(record, PlatformConstants.TRACE_ID_HEADER);
    if (traceId != null) {
      // Restores the correlation id the originating request carried, so log
      // lines on this side join up with the ones that caused them. A trace that
      // dies at the queue boundary defeats the purpose of having one.
      MDC.put(TRACE_ID_KEY, traceId);
    }

    try {
      EventEnvelope<RuleChangedPayload> envelope =
          objectMapper.readValue(
              record.value(), new TypeReference<EventEnvelope<RuleChangedPayload>>() {});

      boolean applied = deploymentService.applyFromEvent(envelope.eventId(), envelope.payload());
      if (applied) {
        LOG.info(
            "Deployed rule {} v{} from {}",
            envelope.payload().ruleId(),
            envelope.payload().version(),
            record.topic());
      }

      // Only after the work has committed. An offset committed before the work
      // is durable is an event silently dropped on the next crash.
      acknowledgment.acknowledge();
    } finally {
      MDC.remove(TRACE_ID_KEY);
    }
  }

  private static String headerValue(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
