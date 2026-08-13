package com.mihir.traffic.common;

/**
 * Constants shared across every service in the platform.
 *
 * <p>Kept deliberately small. Values here must be genuinely cross-cutting: a constant used by one
 * service belongs in that service.
 */
public final class PlatformConstants {

  /**
   * Header carrying a caller-supplied idempotency key on mutating requests.
   *
   * <p>The deployment API uses this to make retries safe: a repeated request with the same key
   * returns the original result rather than performing the action twice. Delivery guarantees in
   * this system are at-least-once, so retries are expected rather than exceptional.
   */
  public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  /**
   * Header carrying the distributed trace identifier across service boundaries.
   *
   * <p>Propagated into Kafka event headers on publish and restored into the logging context on
   * consume. A trace that stops at the queue boundary defeats the purpose of tracing at all.
   */
  public static final String TRACE_ID_HEADER = "X-Trace-Id";

  /** Prefix for every versioned REST endpoint in the control plane. */
  public static final String API_V1 = "/api/v1";

  private PlatformConstants() {
    throw new AssertionError("PlatformConstants is a constant holder and must not be instantiated");
  }
}
