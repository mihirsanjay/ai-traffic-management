package com.mihir.traffic.ruleservice.outbox;

import com.mihir.traffic.common.event.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One event waiting to reach Kafka.
 *
 * <p>A class rather than a record because JPA entities need a no-arg constructor and mutable state.
 *
 * <p>The row is written inside the transaction that makes the change it describes, which is the
 * whole point: the event and the state change are durable together or not at all. Publication
 * happens later and separately, so a row exists in exactly two states — unpublished ({@code
 * publishedAt} null) and published.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

  /**
   * Identity of the event itself, not of this row.
   *
   * <p>Generated once, when the event is created, and never regenerated. Consumers deduplicate on
   * it, so republishing the same row must carry the same id or their idempotency is defeated.
   */
  @Id
  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  /** The Kafka message key. Partitioning by it is what gives per-entity ordering. */
  @Column(name = "aggregate_id", nullable = false, updatable = false)
  private UUID aggregateId;

  @Column(name = "topic", nullable = false, updatable = false, length = 100)
  private String topic;

  @Column(name = "event_type", nullable = false, updatable = false, length = 50)
  private String eventType;

  /** The serialized envelope, sent to Kafka verbatim. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, updatable = false)
  private String payload;

  @Column(name = "trace_id", updatable = false, length = 128)
  private String traceId;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  /** Null until the event reaches Kafka. The only mutable field on this entity. */
  @Column(name = "published_at")
  private Instant publishedAt;

  protected OutboxEvent() {
    // JPA.
  }

  private OutboxEvent(
      UUID eventId,
      UUID aggregateId,
      String topic,
      EventType eventType,
      String payload,
      String traceId,
      Instant occurredAt) {
    this.eventId = eventId;
    this.aggregateId = aggregateId;
    this.topic = topic;
    this.eventType = eventType.name();
    this.payload = payload;
    this.traceId = traceId;
    this.occurredAt = occurredAt;
  }

  /**
   * Creates an unpublished event.
   *
   * @param eventId the envelope's event id, which must already exist rather than being generated
   *     here — the serialized payload carries the same value, and the two must agree
   * @param aggregateId the entity the event concerns; becomes the Kafka key
   * @param topic where it is destined
   * @param eventType what happened
   * @param payload the serialized envelope
   * @param traceId correlation id, or null
   * @param occurredAt when the change happened
   * @return a pending event
   */
  public static OutboxEvent pending(
      UUID eventId,
      UUID aggregateId,
      String topic,
      EventType eventType,
      String payload,
      String traceId,
      Instant occurredAt) {
    return new OutboxEvent(eventId, aggregateId, topic, eventType, payload, traceId, occurredAt);
  }

  /**
   * Marks this event as delivered.
   *
   * @param publishedAt when the broker acknowledged it
   */
  public void markPublished(Instant publishedAt) {
    this.publishedAt = publishedAt;
  }

  public boolean isPublished() {
    return publishedAt != null;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getTopic() {
    return topic;
  }

  public String getEventType() {
    return eventType;
  }

  public String getPayload() {
    return payload;
  }

  public String getTraceId() {
    return traceId;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }
}
