package com.mihir.traffic.ruleservice;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for integration tests: a real PostgreSQL, migrated by Flyway, on a random port.
 *
 * <p>Testcontainers starts its own database rather than using {@code infra/docker-compose.yml}, so
 * tests never depend on what happens to be running locally and never leave state a later test could
 * read. Only the Docker daemon needs to be up.
 *
 * <p>The container is {@code static}, so one instance is shared across every test class in the run
 * rather than paying container startup per class.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot 4 no longer registers a TestRestTemplate bean implicitly for a
// RANDOM_PORT test; it has to be opted into.
@AutoConfigureTestRestTemplate
public abstract class AbstractIntegrationTest {

  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("rule_service")
          .withUsername("traffic")
          .withPassword("traffic");

  static {
    POSTGRES.start();
  }

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  /** Points the application's datasource at the container started above. */
  @TestConfiguration(proxyBeanMethods = false)
  static class ContainerConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
      return POSTGRES;
    }
  }

  /** The HTTP client bound to this instance's random port. */
  protected TestRestTemplate restTemplate() {
    return restTemplate;
  }

  /** Absolute URL for a path on the running server. */
  protected String url(String path) {
    return "http://localhost:" + port + path;
  }
}
