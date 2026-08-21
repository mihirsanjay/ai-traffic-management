package com.mihir.traffic.deploymentservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mihir.traffic.common.event.EventEnvelope;
import com.mihir.traffic.common.event.EventType;
import com.mihir.traffic.common.event.RuleChangedPayload;
import com.mihir.traffic.common.event.Topics;
import com.mihir.traffic.deploymentservice.AbstractIntegrationTest;
import com.mihir.traffic.deploymentservice.domain.Deployment;
import com.mihir.traffic.deploymentservice.domain.DeploymentStatus;
import com.mihir.traffic.deploymentservice.repository.DeploymentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The consumer against a real broker.
 *
 * <p>The duplicate-delivery test is required by {@code CLAUDE.md} and is not optional here: Kafka
 * guarantees at-least-once delivery, so a redelivered event is a certainty rather than an edge
 * case. A consumer that has never been tested against a replay has idempotency as an assumption,
 * not a property.
 */
class RuleEventConsumerIntegrationTest extends AbstractIntegrationTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private DeploymentRepository deploymentRepository;
  @Autowired private ObjectMapper objectMapper;

  // Deliberately no @BeforeEach truncation. Clearing the tables while the topics
  // still hold earlier tests' records makes things worse rather than better: the
  // consumer keeps delivering those records into a now-empty table, so a global
  // count measures other tests' traffic. Each test uses a fresh random rule id
  // and scopes its assertions to it instead, which is isolation that does not
  // depend on the broker being quiet.

  @Test
  @DisplayName("a rule event becomes a deployment")
  void aRuleEventBecomesADeployment() {
    UUID ruleId = UUID.randomUUID();

    publish(Topics.RULE_CREATED, EventType.RULE_CREATED, payload(ruleId, 1, 1000));

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () -> {
              List<Deployment> deployments = deploymentsFor(ruleId);
              assertThat(deployments).hasSize(1);

              Deployment deployment = deployments.get(0);
              assertThat(deployment.getRuleId()).isEqualTo(ruleId);
              assertThat(deployment.getRuleVersion()).isEqualTo(1);
              assertThat(deployment.getService()).isEqualTo("orders");
              assertThat(deployment.getEndpoint()).isEqualTo("/orders");
              assertThat(deployment.getLimitValue()).isEqualTo(1000);
              // The no-op writer cannot fail, so this is SUCCEEDED. Phase 3's
              // writer is what makes FAILED reachable.
              assertThat(deployment.getStatus()).isEqualTo(DeploymentStatus.SUCCEEDED);
            });
  }

  @Test
  @DisplayName("delivering the same event twice changes nothing the second time")
  void aReplayedEventChangesNothing() {
    UUID ruleId = UUID.randomUUID();
    // One envelope, published twice. The same eventId is what makes this a
    // redelivery rather than two different events that happen to look alike -
    // and the event id is exactly what the idempotency ledger keys on.
    EventEnvelope<RuleChangedPayload> envelope =
        EventEnvelope.of(EventType.RULE_CREATED, Instant.now(), null, payload(ruleId, 1, 1000));

    publish(Topics.RULE_CREATED, envelope);
    await().atMost(TIMEOUT).until(() -> deploymentsFor(ruleId).size() == 1);

    Deployment first = deploymentsFor(ruleId).get(0);

    publish(Topics.RULE_CREATED, envelope);

    // Scoped to this test's own rule, not a global count. The tables are cleared
    // between tests but the topics are not, so records published by an earlier
    // test are still being consumed while this one runs - a global count is
    // measuring other tests' traffic as much as its own.
    //
    // Asserting an absence also needs care: "still exactly one deployment" is
    // already true the instant the replay is published, so a single check would
    // pass before the second delivery had even been consumed. Holding the
    // condition for three seconds is what gives the duplicate time to appear if
    // idempotency is broken.
    await()
        .during(Duration.ofSeconds(3))
        .atMost(TIMEOUT)
        .until(() -> deploymentsFor(ruleId).size() == 1);

    List<Deployment> deployments = deploymentsFor(ruleId);
    assertThat(deployments).hasSize(1);

    // The count alone is not enough. A consumer that upserted would keep the
    // count at one while silently rewriting the row, and "provably changes
    // nothing" is the claim being tested.
    Deployment after = deployments.get(0);
    assertThat(after.getDeploymentId()).isEqualTo(first.getDeploymentId());
    assertThat(after.getCreatedAt()).isEqualTo(first.getCreatedAt());
    assertThat(after.getStatus()).isEqualTo(first.getStatus());
  }

  @Test
  @DisplayName("a deletion deploys with no limit to enforce")
  void aDeletionDeploysWithNoLimit() {
    UUID ruleId = UUID.randomUUID();

    publish(
        Topics.RULE_DELETED,
        EventType.RULE_DELETED,
        new RuleChangedPayload(ruleId, 3, "orders", "/orders", null, null, "carol"));

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () -> {
              List<Deployment> deployments = deploymentsFor(ruleId);
              assertThat(deployments).hasSize(1);

              Deployment deployment = deployments.get(0);
              assertThat(deployment.isDeletion()).isTrue();
              assertThat(deployment.getLimitValue()).isNull();
              // The targeting survives: a consumer still has to know which route
              // to stop enforcing.
              assertThat(deployment.getEndpoint()).isEqualTo("/orders");
            });
  }

  /**
   * The deployments for one rule.
   *
   * <p>Every assertion here is scoped this way rather than counting rows globally. The tables are
   * cleared between tests but the Kafka topics are not, so an earlier test's records are still
   * being consumed while this one runs and a global count would measure them too.
   */
  private List<Deployment> deploymentsFor(UUID ruleId) {
    return deploymentRepository.findAll().stream()
        .filter(d -> d.getRuleId().equals(ruleId))
        .toList();
  }

  private static RuleChangedPayload payload(UUID ruleId, int version, int limit) {
    return new RuleChangedPayload(ruleId, version, "orders", "/orders", limit, "1m", "alice");
  }

  private void publish(String topic, EventType eventType, RuleChangedPayload payload) {
    publish(topic, EventEnvelope.of(eventType, Instant.now(), null, payload));
  }

  private void publish(String topic, EventEnvelope<RuleChangedPayload> envelope) {
    kafkaTemplate.send(
        topic, envelope.payload().ruleId().toString(), objectMapper.writeValueAsString(envelope));
  }
}
