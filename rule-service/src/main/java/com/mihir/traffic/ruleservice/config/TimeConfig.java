package com.mihir.traffic.ruleservice.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the service's time source. */
@Configuration
public class TimeConfig {

  /**
   * The wall clock, in UTC.
   *
   * <p>Injected rather than called statically so tests can substitute a fixed clock and assert on
   * timestamps without depending on when they run.
   *
   * @return the system UTC clock
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
