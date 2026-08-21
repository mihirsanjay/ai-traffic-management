package com.mihir.traffic.ruleservice.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link OutboxEvent}. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  /**
   * Claims a batch of unpublished events for this poller.
   *
   * <p>A native query rather than {@code @Lock(PESSIMISTIC_WRITE)} because JPA has no lock mode for
   * {@code SKIP LOCKED}. It can be coaxed out of Hibernate with a magic timeout hint of {@code -2},
   * but that is obscure, Hibernate-specific, and fragile in combination with limits. The SQL is the
   * thing a reviewer needs to see, so it is written plainly.
   *
   * <p>{@code SKIP LOCKED} is what makes more than one publisher instance safe: a row already
   * locked by another poller is passed over rather than waited for, so two pollers claim disjoint
   * batches and neither blocks. Plain {@code FOR UPDATE} would make the second poller wait for the
   * first to commit and then re-read the very same rows.
   *
   * <p><strong>The lock only exists inside a transaction.</strong> Row locks are released at
   * commit, so calling this outside one releases them the instant the query returns and {@code SKIP
   * LOCKED} protects nothing at all — silently. That is why {@link OutboxBatchClaimer} exists as a
   * separate bean rather than this being called straight from the scheduled method.
   *
   * <p>Ordered oldest-first so the backlog drains in the order it accumulated. Per-rule ordering on
   * the Kafka side comes from the partition key, not from this.
   *
   * @param batchSize maximum rows to claim
   * @return the claimed rows, locked until the surrounding transaction commits
   */
  @Query(
      value =
          """
          SELECT * FROM outbox
          WHERE published_at IS NULL
          ORDER BY occurred_at, event_id
          LIMIT :batchSize
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<OutboxEvent> claimUnpublished(@Param("batchSize") int batchSize);

  /**
   * Counts events still waiting to be published.
   *
   * <p>The most useful single number about the health of the event path: a value that climbs and
   * does not fall means the publisher is stuck, and every consumer downstream is working from a
   * stale view of the world.
   *
   * @return the backlog depth
   */
  long countByPublishedAtIsNull();
}
