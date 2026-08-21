package com.mihir.traffic.deploymentservice;

import com.mihir.traffic.common.event.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Base for integration tests: a real PostgreSQL and a real Kafka broker, on a random port.
 *
 * <p>Testcontainers starts both rather than using {@code infra/docker-compose.yml}, so tests never
 * depend on what happens to be running locally and never leave state a later test could read. Only
 * the Docker daemon needs to be up.
 *
 * <p>Containers are {@code static}, so one set is shared across every test class in the run rather
 * than paying startup per class.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // Bound the producer's wait for topic metadata. The default is 60s, so a
      // missing topic costs a full minute per test before failing with a
      // timeout that names the broker rather than the cause. Ten seconds is far
      // longer than a local container needs and short enough to keep a
      // misconfiguration debuggable.
      "spring.kafka.producer.properties.max.block.ms=10000"
    })
// Spring Boot 4 no longer registers a TestRestTemplate bean implicitly for a
// RANDOM_PORT test; it has to be opted into.
@AutoConfigureTestRestTemplate
// Imported explicitly rather than relied on as a nested class: Spring only
// auto-detects a nested @TestConfiguration on the test class actually being
// run, not one inherited from an abstract base. Without this the containers
// start but never reach the datasource or the broker, and the application
// silently falls back to the values in application.yml - which passes locally
// whenever infra/docker-compose.yml happens to be up, and fails everywhere
// else. This cost real time in Phase 1; see docs/learning-map.md.
@Import(AbstractIntegrationTest.ContainerConfiguration.class)
public abstract class AbstractIntegrationTest {

  /**
   * PostgreSQL backing the deployment records.
   *
   * <p>The image matches {@code infra/docker-compose.yml}. Testing against a different major
   * version than the one actually run is how a behavioural difference reaches production unnoticed.
   */
  @SuppressWarnings("resource")
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.1-alpine")
          .withDatabaseName("deployment_service")
          .withUsername("traffic")
          .withPassword("traffic");

  /**
   * The broker.
   *
   * <p>Auto-topic-creation is disabled to match the real broker's configuration. Left on — which is
   * the container's default — a typo'd topic name would silently create a topic in tests and fail
   * against real infrastructure, which is exactly the class of difference Testcontainers exists to
   * eliminate.
   */
  @SuppressWarnings("resource")
  protected static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.1.0").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

  static {
    POSTGRES.start();
    KAFKA.start();
  }

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  /** Wires the containers into the application context. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class ContainerConfiguration {

    /**
     * Exposes the database.
     *
     * @return the container Spring should point the datasource at
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
      return POSTGRES;
    }

    /**
     * Exposes the broker.
     *
     * @return the container Spring should point the Kafka clients at
     */
    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
      return KAFKA;
    }

    /**
     * Creates the topics the consumer subscribes to and dead-letters onto.
     *
     * <p>Needed because auto-creation is disabled above, deliberately.
     *
     * <p>A {@link KafkaAdmin.NewTopics} bean rather than an array of {@link NewTopic}: KafkaAdmin
     * discovers individual NewTopic beans, and an array is one bean of a type it ignores entirely.
     * The symptom is not a wiring error but a producer timing out sixty seconds later on "topic not
     * present in metadata", which points at the broker rather than at this.
     *
     * <p>Three partitions matches {@code infra/kafka/create-topics.sh}, so an ordering bug that
     * only appears across partitions shows up here rather than against real infrastructure.
     *
     * @return the topics to provision at startup
     */
    @Bean
    KafkaAdmin.NewTopics ruleTopics() {
      return new KafkaAdmin.NewTopics(
          topic(Topics.RULE_CREATED),
          topic(Topics.RULE_UPDATED),
          topic(Topics.RULE_DELETED),
          topic(Topics.RULE_CREATED + ".dlt"),
          topic(Topics.RULE_UPDATED + ".dlt"),
          topic(Topics.RULE_DELETED + ".dlt"));
    }

    private static NewTopic topic(String name) {
      return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }
  }

  protected TestRestTemplate restTemplate() {
    return restTemplate;
  }

  protected String url(String path) {
    return "http://localhost:" + port + path;
  }
}
