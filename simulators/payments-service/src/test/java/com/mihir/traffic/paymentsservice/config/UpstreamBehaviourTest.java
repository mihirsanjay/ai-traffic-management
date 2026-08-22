package com.mihir.traffic.paymentsservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The simulator's only real branch: whether a request is delayed, failed, or served. */
class UpstreamBehaviourTest {

  @Test
  void defaultsServeEveryRequestImmediately() {
    // The property that keeps downstream throttling tests deterministic. If
    // this ever fails, every Envoy assertion becomes intermittently red and
    // the flakiness gets blamed on the proxy.
    UpstreamBehaviour behaviour =
        new UpstreamBehaviour(new SimulatorProperties(Duration.ZERO, 0.0));

    assertThatCode(behaviour::applyLatencyAndMaybeFail).doesNotThrowAnyException();
  }

  @Test
  void anErrorRateOfOneFailsEveryRequest() {
    UpstreamBehaviour behaviour =
        new UpstreamBehaviour(new SimulatorProperties(Duration.ZERO, 1.0));

    assertThatExceptionOfType(SimulatedFailureException.class)
        .isThrownBy(behaviour::applyLatencyAndMaybeFail);
  }

  @Test
  void configuredLatencyDelaysTheResponse() {
    Duration latency = Duration.ofMillis(50);
    UpstreamBehaviour behaviour = new UpstreamBehaviour(new SimulatorProperties(latency, 0.0));

    long before = System.nanoTime();
    behaviour.applyLatencyAndMaybeFail();
    Duration elapsed = Duration.ofNanos(System.nanoTime() - before);

    // A lower bound only. Asserting an upper bound here would be asserting on
    // the scheduler, which is what makes timing tests flaky.
    assertThat(elapsed).isGreaterThanOrEqualTo(latency);
  }

  @Test
  void absentPropertiesFallBackToPerfectlyBehaved() {
    SimulatorProperties properties = new SimulatorProperties(null, null);

    assertThat(properties.latency()).isZero();
    assertThat(properties.errorRate()).isZero();
  }
}
