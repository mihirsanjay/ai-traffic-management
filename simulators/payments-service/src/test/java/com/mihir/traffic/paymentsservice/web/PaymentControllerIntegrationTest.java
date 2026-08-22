package com.mihir.traffic.paymentsservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The simulator's one capability: it serves traffic.
 *
 * <p>No Testcontainers, because there is nothing to containerise — a simulator with a database
 * would be a service, and this is a load target. A real port rather than MockMvc, because what
 * matters here is that the thing answers HTTP: Envoy will be the caller, and Envoy does not know
 * what a mock servlet is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PaymentControllerIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void takingAPaymentReturnsItAndThenItAppearsInTheList() {
    ResponseEntity<Payment> taken =
        restTemplate.postForEntity(
            url("/api/v1/payments"),
            Map.of("reference", "INV-1", "amount", new BigDecimal("42.50")),
            Payment.class);

    assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(taken.getBody()).isNotNull();
    assertThat(taken.getBody().paymentId()).isNotNull();
    assertThat(taken.getBody().reference()).isEqualTo("INV-1");
    assertThat(taken.getBody().amount()).isEqualByComparingTo("42.50");

    ResponseEntity<List<Payment>> listed =
        restTemplate.exchange(
            url("/api/v1/payments"),
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<List<Payment>>() {});

    assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(listed.getBody())
        .extracting(Payment::paymentId)
        .contains(taken.getBody().paymentId());
  }

  @Test
  void anInvalidPaymentIsRejectedByTheSimulatorRatherThanAccepted() {
    // A load generator sending junk should see the upstream's own 400. If this
    // returned 500 the fault would look like the platform's.
    ResponseEntity<String> response =
        restTemplate.postForEntity(
            url("/api/v1/payments"),
            Map.of("reference", "", "amount", BigDecimal.ZERO),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void theSimulatorDependsOnNoPlatformModule() {
    // The constraint from docs/roadmap.md, asserted rather than trusted to
    // review: these classes are on the classpath only if a platform dependency
    // was added to this module's POM, at which point the simulator has stopped
    // being an independent upstream.
    assertThat(canLoad("com.mihir.traffic.common.event.EventEnvelope")).isFalse();
    assertThat(canLoad("org.springframework.kafka.core.KafkaTemplate")).isFalse();
    assertThat(canLoad("jakarta.persistence.Entity")).isFalse();
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private static boolean canLoad(String className) {
    try {
      Class.forName(className, false, PaymentControllerIntegrationTest.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      // Absence is the expected outcome, and it is what the caller asserts on.
      return false;
    }
  }
}
