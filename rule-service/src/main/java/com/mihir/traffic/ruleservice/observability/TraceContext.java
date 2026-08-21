package com.mihir.traffic.ruleservice.observability;

import com.mihir.traffic.common.PlatformConstants;
import org.slf4j.MDC;

/**
 * Access to the correlation identifier for the request being handled.
 *
 * <p>Deliberately minimal. This is not distributed tracing — there is no sampling, no span, no
 * propagation format. It is the seam that a real tracer plugs into later, and the reason it exists
 * now is that the identifier must be captured at the moment an event is written to the outbox: the
 * publisher runs on a scheduler thread with no relationship to the request that caused the event,
 * so by then there is nothing left to read.
 *
 * <p>Backed by SLF4J's {@link MDC}, so the same value appears on every log line emitted while
 * handling the request, which is what makes logs and events correlatable at all.
 */
public final class TraceContext {

  /** MDC key under which the correlation id is stored. */
  public static final String TRACE_ID_KEY = "traceId";

  /** Request header carrying an inbound correlation id. */
  public static final String TRACE_ID_HEADER = PlatformConstants.TRACE_ID_HEADER;

  /**
   * Returns the correlation id for the current request.
   *
   * @return the id, or null if this thread is not handling a request that carried one
   */
  public static String currentTraceId() {
    return MDC.get(TRACE_ID_KEY);
  }

  /**
   * Binds a correlation id to the current thread.
   *
   * @param traceId the id to bind
   */
  public static void setTraceId(String traceId) {
    MDC.put(TRACE_ID_KEY, traceId);
  }

  /** Clears the correlation id. Must run on request completion, or a pooled thread leaks it. */
  public static void clear() {
    MDC.remove(TRACE_ID_KEY);
  }

  private TraceContext() {
    throw new AssertionError("TraceContext is a utility holder and must not be instantiated");
  }
}
