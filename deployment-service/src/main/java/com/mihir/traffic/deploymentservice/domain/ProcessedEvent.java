package com.mihir.traffic.deploymentservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A record that one event has already been handled.
 *
 * <p>Kafka delivers at-least-once, so the same event will arrive twice — on a consumer-group
 * rebalance, on redelivery after a failed offset commit, or when the publisher restarts between
 * sending a record and marking its outbox row published. Without this, each redelivery would insert
 * another deployment row and the history would show deployments that never happened.
 *
 * <p>The primary key is the entire mechanism. This row is inserted in the same transaction as the
 * work it guards, so a replay violates the constraint and the whole transaction rolls back. A
 * check-then-insert would read as equivalent and would still race two consumers in the same group
 * during a rebalance; a constraint cannot lose that race.
 *
 * <p><strong>{@link Persistable} is load-bearing, not decoration.</strong> The id is assigned
 * rather than generated, and Spring Data decides insert-versus-update by asking whether the id is
 * null. A non-null id means "existing", so {@code save} calls {@code merge}, which issues a {@code
 * SELECT} and then an {@code UPDATE} when it finds the row — quietly rewriting the ledger entry
 * instead of violating its primary key, and letting the replay through to be processed a second
 * time. Declaring the entity always-new forces a real {@code INSERT}, which is what makes the
 * constraint fire.
 *
 * <p>This was a real bug, caught by the duplicate-delivery test: the same event produced two
 * deployments and nothing failed.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent implements Persistable<UUID> {

  @Id
  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  @Column(name = "processed_at", nullable = false, updatable = false)
  private Instant processedAt;

  protected ProcessedEvent() {
    // JPA.
  }

  private ProcessedEvent(UUID eventId, Instant processedAt) {
    this.eventId = eventId;
    this.processedAt = processedAt;
  }

  /**
   * Marks an event handled.
   *
   * @param eventId the envelope's event id
   * @param processedAt when it was handled
   * @return the record to persist alongside the work it guards
   */
  public static ProcessedEvent of(UUID eventId, Instant processedAt) {
    return new ProcessedEvent(eventId, processedAt);
  }

  @Override
  public UUID getId() {
    return eventId;
  }

  /**
   * Always true: this entity is only ever created, never loaded and re-saved.
   *
   * <p>Marked {@code @Transient} so JPA does not try to map it to a column.
   *
   * @return always true, so Spring Data issues an INSERT rather than a merge
   */
  @Override
  @Transient
  public boolean isNew() {
    return true;
  }

  public UUID getEventId() {
    return eventId;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }
}
