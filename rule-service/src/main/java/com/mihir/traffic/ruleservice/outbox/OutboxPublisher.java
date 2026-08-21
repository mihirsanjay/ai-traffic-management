package com.mihir.traffic.ruleservice.outbox;

import com.mihir.traffic.common.PlatformConstants;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the outbox to Kafka.
 *
 * <p>Runs on a schedule rather than being triggered by the write, because the point of the outbox
 * is to survive the writing process dying: a row must still be published by whatever comes back up.
 *
 * <p>The transactional work is delegated to {@link OutboxBatchClaimer} — see its documentation for
 * why that separation is load-bearing rather than cosmetic. This method itself deliberately holds
 * no transaction while talking to the broker.
 */
@Component
public class OutboxPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);

  /** Kafka header carrying the event id, so a consumer can deduplicate without parsing the body. */
  public static final String EVENT_ID_HEADER = "X-Event-Id";

  private final OutboxBatchClaimer claimer;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final OutboxProperties properties;
  private final Clock clock;

  /**
   * Creates the publisher.
   *
   * @param claimer transactional claim and mark steps
   * @param kafkaTemplate the producer
   * @param properties poll interval, batch size, and send timeout
   * @param clock time source, injected so tests are not dependent on the wall clock
   */
  public OutboxPublisher(
      OutboxBatchClaimer claimer,
      KafkaTemplate<String, String> kafkaTemplate,
      OutboxProperties properties,
      Clock clock) {
    this.claimer = claimer;
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Publishes everything currently unpublished, one batch per invocation.
   *
   * <p>{@code fixedDelay} rather than {@code fixedRate}: the delay is measured from the end of the
   * previous run, so a slow poll cannot overlap with the next one or accumulate a backlog of
   * scheduled invocations behind it.
   *
   * @return the number of events successfully published, so tests can drive this directly instead
   *     of waiting on the scheduler
   */
  @Scheduled(
      fixedDelayString = "${rule-service.outbox.poll-interval:PT1S}",
      initialDelayString = "${rule-service.outbox.initial-delay:PT5S}")
  public int publishPending() {
    if (!properties.enabled()) {
      return 0;
    }

    List<OutboxEvent> claimed = claimer.claim(properties.batchSize());
    if (claimed.isEmpty()) {
      return 0;
    }

    List<UUID> sent = new ArrayList<>(claimed.size());
    for (OutboxEvent event : claimed) {
      if (!publish(event)) {
        // Stop at the first failure rather than pressing on. The failure mode is
        // almost always "the broker is unreachable", which the next event would
        // hit too, and stopping preserves the outbox's order on retry.
        break;
      }
      sent.add(event.getEventId());
    }

    if (!sent.isEmpty()) {
      claimer.markPublished(sent, clock.instant());
    }
    return sent.size();
  }

  private boolean publish(OutboxEvent event) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(
            event.getTopic(), event.getAggregateId().toString(), event.getPayload());

    record
        .headers()
        .add(EVENT_ID_HEADER, event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
    if (event.getTraceId() != null) {
      record
          .headers()
          .add(
              PlatformConstants.TRACE_ID_HEADER,
              event.getTraceId().getBytes(StandardCharsets.UTF_8));
    }

    try {
      // An explicit timeout, because a send future without one waits as long as
      // the producer's own delivery settings allow. One unreachable broker would
      // otherwise stall this loop and the entire backlog behind it.
      kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Interrupted while publishing event {}", event.getEventId());
      return false;
    } catch (ExecutionException | TimeoutException e) {
      // WARN, not ERROR: the row keeps its null published_at and the next poll
      // retries it, so this is degraded-but-handled rather than something a
      // human must act on. It becomes a problem only if it persists, which the
      // outbox backlog gauge is what surfaces.
      LOG.warn(
          "Could not publish event {} to {}, leaving it for the next poll: {}",
          event.getEventId(),
          event.getTopic(),
          e.getMessage());
      return false;
    }
  }
}
