package com.mihir.traffic.ruleservice.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.lettuce.core.RedisException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
// Spring Boot 4 ships Jackson 3, whose packages moved from com.fasterxml.jackson
// to tools.jackson; the Jackson 2 coordinates no longer resolve.
import tools.jackson.databind.ObjectMapper;

/**
 * Rejects callers who exceed their request budget, with counters shared through Redis.
 *
 * <p>This protects the control plane's own API. It is a deliberate stepping stone rather than the
 * platform's enforcement point - Envoy takes over data-plane throttling in Phase 3, and rules
 * stored by this service never reach this filter.
 *
 * <p><strong>Fails open.</strong> If Redis is unreachable the request is allowed rather than
 * rejected. Both directions are defensible and the choice is deliberate: this limiter guards
 * against a runaway client, so losing it degrades a safeguard, while failing closed would convert a
 * Redis outage into a total outage of the control plane. {@code docs/roadmap.md} commits to this
 * behaviour in the Phase 4 hardening table ("Take Redis down - rate limiting fails open, the
 * control plane stays up").
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

  private static final String PROBLEM_BASE = "https://errors.mihir.traffic/rule-service/";
  private static final String API_PREFIX = "/api/";

  private final ProxyManager<byte[]> proxyManager;
  private final Supplier<BucketConfiguration> bucketConfiguration;
  private final RateLimitProperties properties;
  private final ObjectMapper objectMapper;

  /**
   * Creates the filter.
   *
   * @param proxyManager Redis-backed bucket store
   * @param bucketConfiguration shape of each caller's bucket
   * @param properties rate-limit settings
   * @param objectMapper serializer for the problem body
   */
  public RateLimitFilter(
      ProxyManager<byte[]> proxyManager,
      Supplier<BucketConfiguration> bucketConfiguration,
      RateLimitProperties properties,
      ObjectMapper objectMapper) {
    this.proxyManager = proxyManager;
    this.bucketConfiguration = bucketConfiguration;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  /** Only API traffic is limited; actuator health and readiness must stay reachable. */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !properties.enabled() || !request.getRequestURI().startsWith(API_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    ConsumptionProbe probe;
    try {
      BucketProxy bucket = proxyManager.builder().build(keyFor(request), bucketConfiguration);
      probe = bucket.tryConsumeAndReturnRemaining(1);
    } catch (RedisException e) {
      // Fail open, per this class's contract. Continuing is correct because a
      // rate limiter is a safeguard on the API rather than a precondition of
      // it: rejecting traffic here would turn a Redis outage into an outage of
      // the control plane itself. WARN rather than ERROR - the platform is
      // degraded but handled, and nobody needs to be paged.
      //
      // Deliberately narrow: RedisException covers connection loss and command
      // timeouts, while a broader catch would swallow programming errors in
      // the filter itself and silently disable rate limiting for every caller.
      LOG.warn(
          "Rate limiting unavailable, allowing request without a limit, path={}",
          request.getRequestURI(),
          e);
      filterChain.doFilter(request, response);
      return;
    }

    if (probe.isConsumed()) {
      response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
      filterChain.doFilter(request, response);
      return;
    }

    writeTooManyRequests(request, response, probe);
  }

  /**
   * Identifies the caller whose budget is being spent.
   *
   * <p>Keyed on the remote address for now. An authenticated principal replaces this when auth
   * arrives - the same placeholder that {@code RuleController} records for rule authorship.
   */
  private static byte[] keyFor(HttpServletRequest request) {
    String caller = request.getRemoteAddr();
    return ("rate-limit:" + (caller == null ? "unknown" : caller)).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Writes the 429 as RFC 7807, matching {@code GlobalExceptionHandler}'s shape.
   *
   * <p>Serialized here rather than thrown: a filter runs outside the dispatcher, so an exception
   * raised at this point never reaches {@code @RestControllerAdvice} and would surface as the
   * container's default error page.
   */
  private void writeTooManyRequests(
      HttpServletRequest request, HttpServletResponse response, ConsumptionProbe probe)
      throws IOException {

    Duration retryAfter = Duration.ofNanos(probe.getNanosToWaitForRefill());
    // Retry-After is in whole seconds, and rounding down would invite a retry
    // that is still too early; a floor of one second keeps the advice honest.
    long retryAfterSeconds = Math.max(1, retryAfter.toSeconds());

    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.TOO_MANY_REQUESTS,
            "Request budget exhausted. Retry in " + retryAfterSeconds + "s.");
    problem.setType(URI.create(PROBLEM_BASE + "rate-limit-exceeded"));
    problem.setTitle("Too many requests");
    problem.setProperty("correlationId", UUID.randomUUID().toString());

    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
    response.setHeader("X-RateLimit-Remaining", "0");
    objectMapper.writeValue(response.getOutputStream(), problem);

    // INFO, not WARN: a throttled caller is the limiter working as designed,
    // not a fault. Logged because a sustained pattern of these is worth seeing.
    LOG.info(
        "Request throttled, path={} retryAfterSeconds={}",
        request.getRequestURI(),
        retryAfterSeconds);
  }
}
