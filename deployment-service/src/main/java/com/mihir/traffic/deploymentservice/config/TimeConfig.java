package com.mihir.traffic.deploymentservice.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the clock, so nothing reads the wall clock statically and tests can fix time. */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

  /**
   * The system clock in UTC.
   *
   * @return the clock every component should take its time from
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
