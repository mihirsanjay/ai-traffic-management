package com.mihir.traffic.ruleservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mihir.traffic.common.event.EventEnvelope;
import com.mihir.traffic.common.event.EventType;
import com.mihir.traffic.common.event.RuleChangedPayload;
import com.mihir.traffic.ruleservice.AbstractIntegrationTest;
import com.mihir.traffic.ruleservice.outbox.OutboxEvent;
import com.mihir.traffic.ruleservice.outbox.OutboxEventRepository;
import com.mihir.traffic.ruleservice.repository.RuleRepository;
import com.mihir.traffic.ruleservice.repository.RuleVersionRepository;
import com.mihir.traffic.ruleservice.web.dto.CreateRuleRequest;
import com.mihir.traffic.ruleservice.web.dto.RuleResponse;
import com.mihir.traffic.ruleservice.web.dto.UpdateRuleRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the outbox row and the state change it describes are one atomic unit.
 *
 * <p>This is the property the whole pattern exists for, and it is invisible in code review: an
 * outbox write placed outside the transaction looks identical to one placed inside it, and every
 * happy-path test passes either way. The difference only appears when the transaction rolls back.
 *
 * <p>{@code RuleVersionAppender.appendThenFail} is what makes that observable — it writes the
 * version row and then fails deliberately, which is the one interleaving that distinguishes a real
 * transaction from a sequence of independent statements.
 */
class OutboxTransactionIntegrationTest extends AbstractIntegrationTest {

  @Autowired private RuleService ruleService;
  @Autowired private RuleVersionAppender versionAppender;
  @Autowired private RuleRepository ruleRepository;
  @Autowired private RuleVersionRepository ruleVersionRepository;
  @Autowired private OutboxEventRepository outboxRepository;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void clearTables() {
    outboxRepository.deleteAll();
    ruleVersionRepository.deleteAll();
    ruleRepository.deleteAll();
  }

  @Test
  @DisplayName("a rolled-back rule change leaves no outbox row behind")
  void aRolledBackChangeLeavesNoOutboxRow() {
    RuleResponse created =
        ruleService.create(new CreateRuleRequest("orders", "/orders", 1000, "1m"), "alice");
    long afterCreate = outboxRepository.count();

    // The append writes the version row, then the outbox row, then fails. If the
    // outbox write were outside the transaction - or in its own - the event
    // would survive this and announce a version that does not exist.
    assertThatThrownBy(
            () ->
                versionAppender.appendThenFail(
                    created.id(), new UpdateRuleRequest(500, "30s"), "bob"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(outboxRepository.count())
        .as("the failed update's event must have rolled back with it")
        .isEqualTo(afterCreate);
  }

  @Test
  @DisplayName("creating a rule writes exactly one unpublished event")
  void creatingARuleWritesOneUnpublishedEvent() {
    RuleResponse created =
        ruleService.create(new CreateRuleRequest("orders", "/orders", 1000, "1m"), "alice");

    List<OutboxEvent> events = outboxRepository.findAll();
    assertThat(events).hasSize(1);

    OutboxEvent event = events.get(0);
    assertThat(event.getEventType()).isEqualTo(EventType.RULE_CREATED.name());
    assertThat(event.getTopic()).isEqualTo("control.rule.created");
    // The Kafka key. Partitioning by rule id is what gives per-rule ordering.
    assertThat(event.getAggregateId()).isEqualTo(created.id());
    assertThat(event.isPublished()).isFalse();

    RuleChangedPayload payload = payloadOf(event);
    assertThat(payload.service()).isEqualTo("orders");
    assertThat(payload.endpoint()).isEqualTo("/orders");
    assertThat(payload.limitValue()).isEqualTo(1000);
    assertThat(payload.version()).isEqualTo(1);
    assertThat(payload.changedBy()).isEqualTo("alice");
  }

  @Test
  @DisplayName("updating a rule writes an event carrying the new version")
  void updatingARuleWritesAnEventForTheNewVersion() {
    RuleResponse created =
        ruleService.create(new CreateRuleRequest("orders", "/orders", 1000, "1m"), "alice");
    ruleService.update(created.id(), new UpdateRuleRequest(500, "30s"), "bob");

    OutboxEvent update =
        outboxRepository.findAll().stream()
            .filter(e -> EventType.RULE_UPDATED.name().equals(e.getEventType()))
            .findFirst()
            .orElseThrow();

    assertThat(update.getTopic()).isEqualTo("control.rule.updated");
    assertThat(update.getAggregateId()).isEqualTo(created.id());

    RuleChangedPayload payload = payloadOf(update);
    assertThat(payload.version()).isEqualTo(2);
    assertThat(payload.limitValue()).isEqualTo(500);
    assertThat(payload.windowSpec()).isEqualTo("30s");
    assertThat(payload.changedBy()).isEqualTo("bob");
  }

  @Test
  @DisplayName("deleting a rule announces who deleted it, with no limit values")
  void deletingARuleAnnouncesTheDeleter() {
    RuleResponse created =
        ruleService.create(new CreateRuleRequest("orders", "/orders", 1000, "1m"), "alice");
    ruleService.softDelete(created.id(), "carol");

    OutboxEvent deletion =
        outboxRepository.findAll().stream()
            .filter(e -> EventType.RULE_DELETED.name().equals(e.getEventType()))
            .findFirst()
            .orElseThrow();

    assertThat(deletion.getTopic()).isEqualTo("control.rule.deleted");

    // A deleted rule has no limit, but a consumer still needs its targeting in
    // order to stop enforcing it - and "who deleted this" needs the actor.
    RuleChangedPayload payload = payloadOf(deletion);
    assertThat(payload.changedBy()).isEqualTo("carol");
    assertThat(payload.endpoint()).isEqualTo("/orders");
    assertThat(payload.limitValue()).isNull();
    assertThat(payload.windowSpec()).isNull();
  }

  /**
   * Parses a stored event.
   *
   * <p>Parsed rather than string-matched. The column is JSONB, so Postgres stores a normalised
   * document rather than the exact bytes handed to it — key order and whitespace both differ from
   * what Jackson wrote. Asserting on substrings would be asserting on Postgres's formatter, and it
   * is the values that the contract actually promises.
   */
  private RuleChangedPayload payloadOf(OutboxEvent event) {
    EventEnvelope<RuleChangedPayload> envelope =
        objectMapper.readValue(
            event.getPayload(), new TypeReference<EventEnvelope<RuleChangedPayload>>() {});
    return envelope.payload();
  }
}
