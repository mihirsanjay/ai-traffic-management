package com.mihir.traffic.ruleservice.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import io.lettuce.core.RedisConnectionException;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit coverage of the filter's decisions, with Redis stubbed.
 *
 * <p>Bucket arithmetic itself belongs to Bucket4j and is exercised for real against a container in
 * {@link Bucket4jLettuceCompatibilityTest}. What matters here is what the filter does with the
 * answer: allow, reject with a well-formed 429, or fail open.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

  @Mock private ProxyManager<byte[]> proxyManager;
  @Mock private RemoteBucketBuilder<byte[]> bucketBuilder;
  @Mock private BucketProxy bucket;
  @Mock private FilterChain filterChain;

  private RateLimitFilter filter;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  void setUp() {
    filter =
        new RateLimitFilter(
            proxyManager,
            () -> BucketConfiguration.builder().build(),
            new RateLimitProperties(true, 10, Duration.ofMinutes(1), Duration.ofSeconds(1)),
            new ObjectMapper());
    request = new MockHttpServletRequest("GET", "/api/v1/rules");
    request.setRemoteAddr("203.0.113.7");
    response = new MockHttpServletResponse();
  }

  @Test
  void requestWithinBudgetIsPassedDownTheChain() throws Exception {
    givenBucketReturns(ConsumptionProbe.consumed(9, 0));

    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("9");
  }

  @Test
  void requestOverBudgetIsRejectedAsProblemDetail() throws Exception {
    givenBucketReturns(ConsumptionProbe.rejected(0, Duration.ofSeconds(30).toNanos(), 0));

    filter.doFilter(request, response, filterChain);

    // The chain must not run: a throttled request never reaches the controller.
    verify(filterChain, never()).doFilter(any(), any());
    assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
    assertThat(response.getContentAsString())
        .contains("\"status\":429")
        .contains("rate-limit-exceeded")
        .contains("correlationId");
  }

  @Test
  void retryAfterIsNeverZeroForASubSecondWait() throws Exception {
    // Truncating 400ms to 0 would tell the caller to retry immediately, which
    // is advice guaranteed to be refused again.
    givenBucketReturns(ConsumptionProbe.rejected(0, Duration.ofMillis(400).toNanos(), 0));

    filter.doFilter(request, response, filterChain);

    assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
  }

  @Test
  void requestIsAllowedWhenRedisIsUnreachable() throws Exception {
    // Fail open: the limiter is a safeguard on the API, not a precondition of
    // it, so losing Redis must not take the control plane down with it.
    when(proxyManager.builder()).thenThrow(new RedisConnectionException("redis is down"));

    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
  }

  @Test
  void nonApiTrafficIsNotLimited() throws Exception {
    // Actuator probes must stay reachable: a readiness check answering 429
    // would make an over-subscribed instance look dead and get it restarted.
    MockHttpServletRequest actuator = new MockHttpServletRequest("GET", "/actuator/health");

    filter.doFilter(actuator, response, filterChain);

    verify(filterChain).doFilter(actuator, response);
    verify(proxyManager, never()).builder();
  }

  @Test
  void limitingIsSkippedEntirelyWhenDisabled() throws Exception {
    RateLimitFilter disabled =
        new RateLimitFilter(
            proxyManager,
            () -> BucketConfiguration.builder().build(),
            new RateLimitProperties(false, 10, Duration.ofMinutes(1), Duration.ofSeconds(1)),
            new ObjectMapper());

    disabled.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    verify(proxyManager, never()).builder();
  }

  private void givenBucketReturns(ConsumptionProbe probe) {
    when(proxyManager.builder()).thenReturn(bucketBuilder);
    // The Supplier overload is named explicitly: build() is also overloaded for
    // a plain BucketConfiguration, and an untyped any() cannot choose between
    // them.
    when(bucketBuilder.build(
            any(byte[].class), ArgumentMatchers.<Supplier<BucketConfiguration>>any()))
        .thenReturn(bucket);
    when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
  }
}
