package com.mihir.traffic.paymentsservice.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Knobs that make the simulator behave like a real upstream rather than an instant, infallible one.
 *
 * <p>Both default to "perfectly behaved" — no latency, no errors — so that a test asserting on
 * throttling sees only the proxy's behaviour. A simulator that fails 5% of the time by default
 * would make every downstream assertion intermittently red, and the resulting flakiness would be
 * blamed on the platform.
 *
 * @param latency artificial delay added before each response
 * @param errorRate fraction of requests answered with 500, from 0.0 to 1.0
 */
@Validated
@ConfigurationProperties(prefix = "simulator")
public record SimulatorProperties(
    @NotNull Duration latency, @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double errorRate) {

  /** Defaults chosen so tests are deterministic unless a test asks otherwise. */
  public SimulatorProperties {
    if (latency == null) {
      latency = Duration.ZERO;
    }
    if (errorRate == null) {
      errorRate = 0.0;
    }
  }
}
