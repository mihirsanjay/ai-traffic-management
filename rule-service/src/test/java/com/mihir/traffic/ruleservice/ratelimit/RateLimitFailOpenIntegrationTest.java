package com.mihir.traffic.ruleservice.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the limiter fails open when Redis is gone.
 *
 * <p>The decision is deliberate and documented on {@link RateLimitFilter}: this limiter is a
 * safeguard on the control plane's API, not a precondition of it, so a Redis outage must cost the
 * safeguard rather than the service. {@code docs/roadmap.md} commits to the same behaviour in the
 * Phase 4 hardening table.
 *
 * <p>Redis is really stopped rather than stubbed. A mocked failure would prove the catch block
 * runs; only killing the dependency proves the timeout fires and the request still completes.
 *
 * <p>Owns its containers instead of extending {@code AbstractIntegrationTest}, because stopping the
 * shared Redis would break every other test in the run.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "rule-service.rate-limit.enabled=true",
      "rule-service.rate-limit.capacity=100",
      // Short, so a stopped Redis fails fast rather than stalling the test for
      // as long as the production default would allow.
      "rule-service.rate-limit.redis-timeout=500ms"
    })
class RateLimitFailOpenIntegrationTest {

  private static final int REDIS_CONTAINER_PORT = 6379;

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("rule_service")
          .withUsername("traffic")
          .withPassword("traffic");

  @SuppressWarnings("resource")
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.2-alpine").withExposedPorts(REDIS_CONTAINER_PORT);

  static {
    POSTGRES.start();
    REDIS.start();
  }

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_CONTAINER_PORT));
  }

  @AfterAll
  static void stopContainers() {
    POSTGRES.stop();
    REDIS.stop();
  }

  @Test
  void apiKeepsServingWhenRedisIsDown() {
    RestTemplate client = new RestTemplate();
    client.setRequestFactory(requestFactoryWithTimeouts());

    // Healthy first: establishes that the endpoint works and that the failure
    // below is caused by Redis rather than by an already-broken service.
    assertThat(get(client)).isEqualTo(HttpStatus.OK);

    REDIS.stop();

    // Redis is gone. Failing open means this is still a 200 - not a 429, and
    // not a 500. A limiter that took the API down with its counter store would
    // fail here, which is the whole point of the assertion.
    assertThat(get(client))
        .as("a Redis outage must cost rate limiting, not availability")
        .isEqualTo(HttpStatus.OK);
  }

  private HttpStatus get(RestTemplate client) {
    ResponseEntity<String> response =
        client.getForEntity("http://localhost:" + port + "/api/v1/rules", String.class);
    return HttpStatus.valueOf(response.getStatusCode().value());
  }

  private static org.springframework.http.client.ClientHttpRequestFactory
      requestFactoryWithTimeouts() {
    var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    // Bounded so a hung request fails the test rather than hanging the suite -
    // generous enough to outlast the filter's own Redis timeout.
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(10));
    return factory;
  }
}
