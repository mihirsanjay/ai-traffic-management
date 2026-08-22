package com.mihir.traffic.ordersservice.config;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Applies the configured latency and error rate to a request.
 *
 * <p>Extracted from the controller so the controller stays a controller: this is the one piece of
 * the simulator with behaviour worth testing on its own.
 */
@Component
public class UpstreamBehaviour {

  private final SimulatorProperties properties;

  /**
   * Creates the behaviour shaper.
   *
   * @param properties the configured latency and error rate
   */
  public UpstreamBehaviour(SimulatorProperties properties) {
    this.properties = properties;
  }

  /**
   * Waits out the configured latency.
   *
   * <p>A blocking sleep is correct here rather than lazy: this simulates an upstream that is
   * genuinely slow, and Spring Boot 4 serves these requests on virtual threads, so a parked request
   * does not hold a platform thread.
   *
   * @throws SimulatedFailureException if this request is one the configured error rate fails
   */
  public void applyLatencyAndMaybeFail() {
    if (!properties.latency().isZero() && !properties.latency().isNegative()) {
      try {
        Thread.sleep(properties.latency());
      } catch (InterruptedException e) {
        // Restore the flag and stop waiting. Swallowing the interrupt would
        // leave a shutting-down container waiting out the full delay.
        Thread.currentThread().interrupt();
        throw new SimulatedFailureException("Interrupted while simulating latency");
      }
    }

    if (properties.errorRate() > 0.0
        && ThreadLocalRandom.current().nextDouble() < properties.errorRate()) {
      throw new SimulatedFailureException("Simulated upstream failure");
    }
  }
}
