package com.mihir.traffic.paymentsservice.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the time source, injected rather than called statically so tests can fix it. */
@Configuration
public class TimeConfig {

  /**
   * The system clock, in UTC.
   *
   * @return a UTC clock
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
