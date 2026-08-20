package com.mihir.traffic.common.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * The wrapper every published event shares, carrying transport metadata around a domain payload.
 *
 * <p>Generic and deliberately ignorant of any particular domain: adding deployment or traffic
 * events later requires no change to this record. The payload types are what vary.
 *
 * <p><strong>Event schemas are a public API.</strong> Only additive changes are allowed — no field
 * removal, no type narrowing — because a producer and a consumer are deployed separately and will
 * disagree about the schema for as long as a rollout takes. Two mechanisms make that survivable:
 *
 * <ul>
 *   <li>{@code @JsonIgnoreProperties(ignoreUnknown = true)}, so a <em>new</em> producer's extra
 *       field does not break an <em>old</em> consumer. This is the single most load-bearing
 *       annotation in the module and the reason it depends on Jackson at all.
 *   <li>{@link #payloadVersion()}, the escape hatch for a change that genuinely cannot be additive.
 *       Without it the only remedy is a new topic, which is far more expensive.
 * </ul>
 *
 * <p>Note that adding a component to a record changes its canonical constructor, and an old
 * document deserialized into a new record leaves the new component null. New payload fields must
 * therefore be nullable and never required.
 *
 * @param eventId unique identity of this event, generated once when the event is written to the
 *     outbox and stable across every republish attempt — consumers deduplicate on it
 * @param eventType what happened
 * @param occurredAt when it happened, UTC
 * @param traceId correlation identifier propagated from the originating request, or null if there
 *     was none
 * @param payloadVersion schema version of the payload; currently always {@link #CURRENT_VERSION}
 * @param payload the domain data
 * @param <T> the payload type
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T>(
    UUID eventId,
    EventType eventType,
    Instant occurredAt,
    String traceId,
    int payloadVersion,
    T payload) {

  /** The payload schema version currently produced. */
  public static final int CURRENT_VERSION = 1;

  /**
   * Creates an envelope with a fresh event ID at the current schema version.
   *
   * @param eventType what happened
   * @param occurredAt when it happened; passed in rather than read from the wall clock so callers
   *     can use the same instant as the state change the event describes
   * @param traceId correlation identifier, or null
   * @param payload the domain data
   * @param <T> the payload type
   * @return a new envelope
   */
  public static <T> EventEnvelope<T> of(
      EventType eventType, Instant occurredAt, String traceId, T payload) {
    return new EventEnvelope<>(
        UUID.randomUUID(), eventType, occurredAt, traceId, CURRENT_VERSION, payload);
  }
}
