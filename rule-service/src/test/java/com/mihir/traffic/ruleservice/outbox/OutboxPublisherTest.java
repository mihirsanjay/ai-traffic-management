package com.mihir.traffic.ruleservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mihir.traffic.common.PlatformConstants;
import com.mihir.traffic.common.event.EventType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

/** Publisher behaviour that does not need a broker to establish. */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

  private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

  @Mock private OutboxBatchClaimer claimer;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private OutboxPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher =
        new OutboxPublisher(
            claimer,
            kafkaTemplate,
            new OutboxProperties(true, Duration.ofSeconds(1), 10, Duration.ofSeconds(1)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static OutboxEvent event(String traceId) {
    UUID eventId = UUID.randomUUID();
    return OutboxEvent.pending(
        eventId,
        UUID.randomUUID(),
        "control.rule.created",
        EventType.RULE_CREATED,
        "{\"eventId\":\"" + eventId + "\"}",
        traceId,
        NOW);
  }

  @Test
  @DisplayName("an empty outbox does not touch Kafka at all")
  void anEmptyOutboxSkipsKafkaEntirely() {
    when(claimer.claim(anyInt())).thenReturn(List.of());

    assertThat(publisher.publishPending()).isZero();

    verifyNoInteractions(kafkaTemplate);
    verify(claimer, never()).markPublished(anyList(), any());
  }

  @Test
  @DisplayName("a published event is keyed by its aggregate so per-rule ordering holds")
  void publishedEventsAreKeyedByAggregateId() {
    OutboxEvent pending = event("trace-42");
    when(claimer.claim(anyInt())).thenReturn(List.of(pending));
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    assertThat(publisher.publishPending()).isEqualTo(1);

    ArgumentCaptor<ProducerRecord<String, String>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate).send(sent.capture());

    ProducerRecord<String, String> record = sent.getValue();
    assertThat(record.topic()).isEqualTo("control.rule.created");
    // Kafka orders within a partition and nowhere else, so the key is what makes
    // two updates to one rule arrive in the order they were made.
    assertThat(record.key()).isEqualTo(pending.getAggregateId().toString());
    assertThat(record.value()).isEqualTo(pending.getPayload());
    assertThat(header(record, OutboxPublisher.EVENT_ID_HEADER))
        .isEqualTo(pending.getEventId().toString());
    assertThat(header(record, PlatformConstants.TRACE_ID_HEADER)).isEqualTo("trace-42");

    verify(claimer).markPublished(List.of(pending.getEventId()), NOW);
  }

  @Test
  @DisplayName("an event with no trace id omits the header rather than sending null")
  void aMissingTraceIdOmitsTheHeader() {
    OutboxEvent pending = event(null);
    when(claimer.claim(anyInt())).thenReturn(List.of(pending));
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    publisher.publishPending();

    ArgumentCaptor<ProducerRecord<String, String>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate).send(sent.capture());
    assertThat(sent.getValue().headers().lastHeader(PlatformConstants.TRACE_ID_HEADER)).isNull();
  }

  @Test
  @DisplayName("a failed send leaves the event unpublished for the next poll")
  void aFailedSendLeavesTheEventForTheNextPoll() {
    when(claimer.claim(anyInt())).thenReturn(List.of(event(null)));
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

    // The failure is handled, not propagated: a scheduled method that throws
    // would just log a stack trace, and the row is retried either way.
    assertThat(publisher.publishPending()).isZero();

    // Nothing is marked published, so published_at stays null and the row is
    // claimed again next time. An outbox row describes a committed change and
    // is therefore never abandoned.
    verify(claimer, never()).markPublished(anyList(), any());
  }

  @Test
  @DisplayName("a failure part-way through a batch still records what did send")
  void aPartialBatchRecordsWhatSucceeded() {
    OutboxEvent first = event(null);
    OutboxEvent second = event(null);
    when(claimer.claim(anyInt())).thenReturn(List.of(first, second));
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(null))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

    assertThat(publisher.publishPending()).isEqualTo(1);

    // Only the first is marked. Marking both would lose the second; marking
    // neither would republish the first, which is merely a duplicate and safe -
    // but there is no reason to be imprecise when the information is available.
    verify(claimer).markPublished(List.of(first.getEventId()), NOW);
  }

  @Test
  @DisplayName("a disabled publisher does nothing at all")
  void aDisabledPublisherDoesNothing() {
    OutboxPublisher disabled =
        new OutboxPublisher(
            claimer,
            kafkaTemplate,
            new OutboxProperties(false, Duration.ofSeconds(1), 10, Duration.ofSeconds(1)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(disabled.publishPending()).isZero();

    verifyNoInteractions(claimer, kafkaTemplate);
  }

  private static String header(ProducerRecord<String, String> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }
}
