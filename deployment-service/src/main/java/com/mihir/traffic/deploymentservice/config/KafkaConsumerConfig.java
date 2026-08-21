package com.mihir.traffic.deploymentservice.config;

import org.apache.kafka.common.errors.SerializationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/** Consumer error handling. */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {

  /** Three attempts, one second apart. */
  private static final int MAX_ATTEMPTS = 3;

  private static final long RETRY_INTERVAL_MS = 1_000L;

  /**
   * Retries a failed record a few times, then routes it to a dead-letter topic.
   *
   * <p>Without this, a record that always fails is retried forever and every record behind it on
   * the same partition waits — one bad message stops the service. The dead-letter topics already
   * exist (see {@code infra/kafka/create-topics.sh}), and the recoverer's default naming of {@code
   * <topic>.dlt} matches them.
   *
   * <p>Deserialization failures are not retried. A payload that cannot be parsed will not parse on
   * the second attempt either, so retrying only delays the inevitable and burns the partition in
   * the meantime.
   *
   * <p>Letting the framework own the broad catch is also what keeps application code free of one:
   * the project's Checkstyle rules ban catching {@code RuntimeException}, and a consumer that
   * needed to would be hiding what it actually handles.
   *
   * @param template publishes the dead-lettered record
   * @return the error handler for every listener container
   */
  @Bean
  public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            // Spring Kafka's default suffix is "-dlt"; this project's topics are
            // named "<topic>.dlt" (infra/kafka/create-topics.sh). Left at the
            // default, every dead-letter publish fails with "topic not present
            // in metadata" against a broker that has auto-creation off - and
            // because the recoverer's own failure is a listener failure, the
            // record is then redelivered forever. The naming has to agree.
            (record, exception) ->
                new org.apache.kafka.common.TopicPartition(
                    record.topic() + ".dlt", record.partition()));

    DefaultErrorHandler handler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_ATTEMPTS));
    handler.addNotRetryableExceptions(JacksonException.class, SerializationException.class);
    return handler;
  }
}
