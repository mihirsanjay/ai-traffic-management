package com.mihir.traffic.ruleservice.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wiring for the outbox.
 *
 * <p>The properties are registered unconditionally — {@link OutboxPublisher} needs them whether or
 * not it is being driven by a scheduler.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

  /**
   * Turns on the background poll.
   *
   * <p>Separated from the properties above so integration tests can set {@code
   * rule-service.outbox.enabled=false} and call {@link OutboxPublisher#publishPending()} directly.
   * Driving the publisher explicitly is what makes those tests deterministic: no waiting on a
   * scheduler, no sleeping, and no race between a test's assertions and a background poll running
   * concurrently with them.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "rule-service.outbox", name = "enabled", havingValue = "true")
  @EnableScheduling
  static class SchedulingConfig {}
}
