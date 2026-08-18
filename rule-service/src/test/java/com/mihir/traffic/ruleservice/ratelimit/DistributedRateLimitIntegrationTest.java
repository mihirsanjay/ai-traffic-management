package com.mihir.traffic.ruleservice.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mihir.traffic.ruleservice.AbstractIntegrationTest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

/**
 * Proves the limit is genuinely shared, not per instance.
 *
 * <p>This is the test the Phase 1 exit criterion actually asks for: <em>"Bucket4j throttles a test
 * endpoint, and the limit holds across two running instances sharing Redis."</em> A limiter holding
 * its bucket in local memory passes every single-instance test and still fails here, because two
 * instances would then serve twice the configured limit. Running one budget across two independent
 * Spring contexts is the only thing that distinguishes the two designs.
 */
@TestPropertySource(
    properties = {
      "rule-service.rate-limit.enabled=true",
      "rule-service.rate-limit.capacity=4",
      // No refill during the test: a token returning mid-run would let a
      // request through that the shared budget should have refused.
      "rule-service.rate-limit.refill-period=1h"
    })
class DistributedRateLimitIntegrationTest extends AbstractIntegrationTest {

  private static final String RULES = "/api/v1/rules";
  private static final int CAPACITY = 4;

  /** Redis's port inside the container; the host maps it to a random one. */
  private static final int REDIS_CONTAINER_PORT = 6379;

  @Autowired private RedisClient redisClient;

  @LocalServerPort private int firstInstancePort;

  @BeforeEach
  void clearBuckets() {
    try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
      connection.sync().flushall();
    }
  }

  @Test
  void budgetIsSharedAcrossTwoInstances() {
    // A second, independent application on its own port, pointed at the same
    // Postgres and - crucially - the same Redis.
    try (ConfigurableApplicationContext secondInstance = startSecondInstance()) {
      int secondInstancePort =
          Integer.parseInt(secondInstance.getEnvironment().getProperty("local.server.port", "0"));
      assertThat(secondInstancePort)
          .as("second instance should be listening on its own port")
          .isNotZero()
          .isNotEqualTo(firstInstancePort);

      RestTemplate client = new RestTemplate();
      // A 4xx must not raise, so the status can be asserted directly.
      client.setErrorHandler(new PermissiveErrorHandler());

      // Alternate between the instances while spending the whole budget. If
      // each kept its own bucket, every one of these would be allowed - and
      // the assertion after the loop would fail.
      List<HttpStatus> statuses = new ArrayList<>();
      for (int i = 0; i < CAPACITY; i++) {
        int port = (i % 2 == 0) ? firstInstancePort : secondInstancePort;
        statuses.add(get(client, port));
      }

      assertThat(statuses)
          .as(
              "the shared budget covers exactly %d requests, whichever instance serves them",
              CAPACITY)
          .containsOnly(HttpStatus.OK);

      // The budget is spent. Both instances must now refuse, including the one
      // that served only half the traffic.
      assertThat(get(client, secondInstancePort))
          .as("second instance must see the budget spent by the first")
          .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
      assertThat(get(client, firstInstancePort))
          .as("first instance must see the budget spent by the second")
          .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
  }

  private ConfigurableApplicationContext startSecondInstance() {
    // Every setting that application.yml also defines is passed as a
    // command-line argument, which outranks the config file. A properties()
    // entry ranks *below* it, so spring.data.redis.port silently lost to the
    // committed 6379 and the second instance connected to whatever Redis
    // happened to be running locally - sharing no bucket with the first, and
    // making a genuinely broken limiter look like a passing distributed one.
    return new SpringApplicationBuilder(com.mihir.traffic.ruleservice.RuleServiceApplication.class)
        .properties(
            // Flyway already ran on the first instance; this one shares the
            // schema rather than migrating it again.
            "spring.flyway.enabled=false",
            "rule-service.rate-limit.enabled=true",
            "rule-service.rate-limit.capacity=" + CAPACITY,
            "rule-service.rate-limit.refill-period=1h")
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(REDIS_CONTAINER_PORT));
  }

  private HttpStatus get(RestTemplate client, int port) {
    ResponseEntity<String> response =
        client.getForEntity("http://localhost:" + port + RULES, String.class);
    return HttpStatus.valueOf(response.getStatusCode().value());
  }

  /** Returns 4xx responses instead of throwing, so the status can be asserted. */
  private static final class PermissiveErrorHandler
      implements org.springframework.web.client.ResponseErrorHandler {

    @Override
    public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
      return false;
    }
  }
}
