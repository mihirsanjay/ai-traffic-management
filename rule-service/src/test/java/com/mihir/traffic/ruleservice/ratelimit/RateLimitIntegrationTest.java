package com.mihir.traffic.ruleservice.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mihir.traffic.ruleservice.AbstractIntegrationTest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The rate limit as a caller experiences it, over real HTTP against a real Redis.
 *
 * <p>Re-enables limiting, which {@link AbstractIntegrationTest} switches off so that CRUD tests are
 * not coupled to a request budget. The capacity is small so the limit is reachable in a handful of
 * requests rather than a hundred.
 */
@TestPropertySource(
    properties = {
      "rule-service.rate-limit.enabled=true",
      "rule-service.rate-limit.capacity=3",
      // Long enough that no refill happens mid-test: a token trickling back
      // would make the assertions depend on how fast the suite runs.
      "rule-service.rate-limit.refill-period=1h"
    })
class RateLimitIntegrationTest extends AbstractIntegrationTest {

  private static final String RULES = "/api/v1/rules";

  @Autowired private RedisClient redisClient;

  /**
   * Empties the shared bucket store before each test.
   *
   * <p>Buckets are keyed on caller address and every test here calls from the same loopback
   * address, so without this the first test spends the budget and the next one starts throttled.
   * That would make results depend on execution order, which the standards rule out.
   */
  @BeforeEach
  void clearBuckets() {
    try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
      connection.sync().flushall();
    }
  }

  @Test
  void callerIsThrottledOnceTheBudgetIsSpent() {
    // Capacity is 3, so the first three succeed and the fourth must not.
    for (int i = 1; i <= 3; i++) {
      ResponseEntity<String> allowed = restTemplate().getForEntity(url(RULES), String.class);
      assertThat(allowed.getStatusCode())
          .as("request %d of the 3-token budget", i)
          .isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<ProblemDetail> throttled =
        restTemplate().getForEntity(url(RULES), ProblemDetail.class);

    assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(throttled.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    // Retry-After tells the caller when to come back; without it a client can
    // only guess, and guessing usually means retrying immediately.
    assertThat(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();

    ProblemDetail problem = throttled.getBody();
    assertThat(problem).isNotNull();
    assertThat(problem.getType().toString()).endsWith("rate-limit-exceeded");
    assertThat(problem.getTitle()).isEqualTo("Too many requests");
    assertThat(problem.getProperties()).containsKey("correlationId");
  }

  @Test
  void remainingBudgetIsReportedOnAllowedRequests() {
    ResponseEntity<String> first = restTemplate().getForEntity(url(RULES), String.class);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(first.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("2");
  }
}
