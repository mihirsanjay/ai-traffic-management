package com.mihir.traffic.ruleservice.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two short transactions either side of publishing.
 *
 * <p>A separate bean from {@link OutboxPublisher}, and this is not stylistic. Spring applies
 * {@code @Transactional} through a proxy, so calling these methods on {@code this} from the
 * scheduled method would bypass the annotation entirely — the same trap ADR 0008 documents. Here it
 * would be worse than usual: {@code FOR UPDATE SKIP LOCKED} outside a transaction releases its
 * locks the moment the query returns, so the claim would appear to work while protecting nothing at
 * all, and two pollers would quietly publish the same events.
 *
 * <p>The transactions are deliberately short and do not span the Kafka send. Holding one open
 * across a remote call is how connection pools die, so the sequence is claim, commit, publish, then
 * record — which leaves a window in which a crashed publisher republishes on restart. That is
 * at-least-once, which consumers already handle by keying on the event id (ADR 0005). The inverse
 * arrangement, marking rows published before sending them, would be at-most-once and would lose
 * events outright.
 */
@Component
public class OutboxBatchClaimer {

  private final OutboxEventRepository repository;

  /**
   * Creates the claimer.
   *
   * @param repository persistence for outbox rows
   */
  public OutboxBatchClaimer(OutboxEventRepository repository) {
    this.repository = repository;
  }

  /**
   * Claims a batch of unpublished events.
   *
   * @param batchSize maximum rows to claim
   * @return the claimed events, oldest first
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<OutboxEvent> claim(int batchSize) {
    return repository.claimUnpublished(batchSize);
  }

  /**
   * Marks events as delivered.
   *
   * <p>Rows that failed to send are simply not passed here: they keep a null {@code published_at}
   * and are picked up by the next poll. An outbox row describes a change that has already
   * committed, so it is never abandoned.
   *
   * @param eventIds the events the broker acknowledged
   * @param publishedAt when they were acknowledged
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markPublished(List<UUID> eventIds, Instant publishedAt) {
    for (OutboxEvent event : repository.findAllById(eventIds)) {
      event.markPublished(publishedAt);
    }
  }
}
