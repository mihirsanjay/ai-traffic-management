package com.mihir.traffic.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The event contract, expressed as executable assertions.
 *
 * <p>These events cross a process boundary as JSON, so the wire format is the actual API. A test
 * that only round-trips an object through a mapper proves the mapper works; these additionally
 * assert the field names on the wire and that an unknown field does not break deserialization,
 * which is the property the additive-only rule depends on.
 */
class EventSerializationTest {

  private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  @Test
  @DisplayName("a rule event survives a round trip through JSON unchanged")
  void ruleEventRoundTripsThroughJson() throws Exception {
    RuleChangedPayload payload =
        new RuleChangedPayload(
            UUID.randomUUID(), 3, "orders", "/orders", 500, "1m", "someone@example.com");
    EventEnvelope<RuleChangedPayload> original =
        EventEnvelope.of(
            EventType.RULE_UPDATED, Instant.parse("2026-08-20T12:00:00Z"), "abc", payload);

    String json = mapper.writeValueAsString(original);
    EventEnvelope<RuleChangedPayload> restored =
        mapper.readValue(json, new TypeReference<EventEnvelope<RuleChangedPayload>>() {});

    assertThat(restored).isEqualTo(original);
  }

  @Test
  @DisplayName("the envelope's field names on the wire are the ones consumers expect")
  void envelopeFieldNamesAreStable() throws Exception {
    EventEnvelope<RuleChangedPayload> event =
        EventEnvelope.of(
            EventType.RULE_CREATED,
            Instant.parse("2026-08-20T12:00:00Z"),
            "trace-1",
            new RuleChangedPayload(UUID.randomUUID(), 1, "orders", "/orders", 100, "1m", "system"));

    String json = mapper.writeValueAsString(event);

    // Renaming any of these silently breaks every deployed consumer, so they are
    // asserted by name rather than only by round trip.
    assertThat(json)
        .contains("\"eventId\"")
        .contains("\"eventType\":\"RULE_CREATED\"")
        .contains("\"occurredAt\"")
        .contains("\"traceId\":\"trace-1\"")
        .contains("\"payloadVersion\":1")
        .contains("\"payload\"");
  }

  @Test
  @DisplayName("an unknown field from a newer producer does not break an older consumer")
  void unknownFieldsAreIgnored() {
    // The additive-only contract in CLAUDE.md is only worth anything if an old
    // consumer genuinely tolerates a new producer's extra fields. This is that
    // guarantee as a test rather than as a promise.
    String fromANewerProducer =
        """
        {
          "eventId": "0b5d2d3e-7a1e-4f3a-9c2b-1d4e5f6a7b8c",
          "eventType": "RULE_UPDATED",
          "occurredAt": "2026-08-20T12:00:00Z",
          "traceId": "trace-1",
          "payloadVersion": 1,
          "unknownEnvelopeField": "added in some future version",
          "payload": {
            "ruleId": "1a2b3c4d-5e6f-4a1b-8c9d-0e1f2a3b4c5d",
            "version": 4,
            "service": "orders",
            "endpoint": "/orders",
            "limitValue": 250,
            "windowSpec": "1m",
            "changedBy": "system",
            "unknownPayloadField": 42
          }
        }
        """;

    assertThatCode(
            () ->
                mapper.readValue(
                    fromANewerProducer, new TypeReference<EventEnvelope<RuleChangedPayload>>() {}))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a deletion carries null values rather than omitting the rule's identity")
  void deletionPayloadHasNullValuesButKeepsIdentity() throws Exception {
    UUID ruleId = UUID.randomUUID();
    RuleChangedPayload deletion =
        new RuleChangedPayload(ruleId, 7, "orders", "/orders", null, null, "system");

    String json = mapper.writeValueAsString(deletion);
    RuleChangedPayload restored = mapper.readValue(json, RuleChangedPayload.class);

    assertThat(restored.ruleId()).isEqualTo(ruleId);
    assertThat(restored.version()).isEqualTo(7);
    assertThat(restored.limitValue()).isNull();
    assertThat(restored.windowSpec()).isNull();
  }

  @Test
  @DisplayName("a deployment outcome survives a round trip")
  void deploymentOutcomeRoundTrips() throws Exception {
    DeploymentOutcomePayload payload =
        new DeploymentOutcomePayload(UUID.randomUUID(), UUID.randomUUID(), 2, "FAILED", "timeout");
    EventEnvelope<DeploymentOutcomePayload> original =
        EventEnvelope.of(
            EventType.DEPLOYMENT_FAILED, Instant.parse("2026-08-20T12:00:00Z"), null, payload);

    String json = mapper.writeValueAsString(original);
    EventEnvelope<DeploymentOutcomePayload> restored =
        mapper.readValue(json, new TypeReference<EventEnvelope<DeploymentOutcomePayload>>() {});

    assertThat(restored).isEqualTo(original);
    assertThat(restored.traceId()).isNull();
  }

  @Test
  @DisplayName("each envelope gets its own event id")
  void eachEnvelopeGetsAFreshEventId() {
    Instant now = Instant.parse("2026-08-20T12:00:00Z");
    RuleChangedPayload payload =
        new RuleChangedPayload(UUID.randomUUID(), 1, "orders", "/orders", 100, "1m", "system");

    EventEnvelope<RuleChangedPayload> first =
        EventEnvelope.of(EventType.RULE_CREATED, now, null, payload);
    EventEnvelope<RuleChangedPayload> second =
        EventEnvelope.of(EventType.RULE_CREATED, now, null, payload);

    assertThat(first.eventId()).isNotEqualTo(second.eventId());
    assertThat(first.payloadVersion()).isEqualTo(EventEnvelope.CURRENT_VERSION);
  }

  @Test
  @DisplayName("rule event types map to their topics, and deployment types are rejected")
  void ruleEventTypesMapToTopics() {
    assertThat(Topics.forRuleEvent(EventType.RULE_CREATED)).isEqualTo("control.rule.created");
    assertThat(Topics.forRuleEvent(EventType.RULE_UPDATED)).isEqualTo("control.rule.updated");
    assertThat(Topics.forRuleEvent(EventType.RULE_DELETED)).isEqualTo("control.rule.deleted");

    assertThatThrownBy(() -> Topics.forRuleEvent(EventType.DEPLOYMENT_SUCCEEDED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a rule event");
  }
}
