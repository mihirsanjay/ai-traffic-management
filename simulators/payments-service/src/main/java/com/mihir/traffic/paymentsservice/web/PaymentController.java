package com.mihir.traffic.paymentsservice.web;

import com.mihir.traffic.paymentsservice.config.UpstreamBehaviour;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payments API: the traffic the platform exists to manage.
 *
 * <p>In-memory state, capped, and lost on restart — this is a load target, not a system of record.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

  /**
   * Cap on retained payments. Without one, a sustained load run is an OutOfMemoryError with extra
   * steps, and the simulator would fail before the platform under test did.
   */
  private static final int MAX_RETAINED = 1_000;

  /**
   * Recent payments, newest first. A deque because the only two operations are "add at the head"
   * and "evict from the tail"; concurrent because Spring serves requests from many threads and this
   * field is shared across all of them.
   */
  private final ConcurrentLinkedDeque<Payment> payments = new ConcurrentLinkedDeque<>();

  private final UpstreamBehaviour behaviour;
  private final Clock clock;

  /**
   * Creates the controller.
   *
   * @param behaviour applies the configured latency and error rate
   * @param clock time source, injected so tests are not dependent on the wall clock
   */
  public PaymentController(UpstreamBehaviour behaviour, Clock clock) {
    this.behaviour = behaviour;
    this.clock = clock;
  }

  /**
   * Lists recent payments, newest first.
   *
   * @return the retained payments
   */
  @GetMapping
  public List<Payment> list() {
    behaviour.applyLatencyAndMaybeFail();
    return List.copyOf(payments);
  }

  /**
   * Takes a payment.
   *
   * @param request what to take
   * @return the taken payment
   */
  @PostMapping
  public ResponseEntity<Payment> take(@Valid @RequestBody TakePaymentRequest request) {
    behaviour.applyLatencyAndMaybeFail();

    Payment payment =
        new Payment(UUID.randomUUID(), request.reference(), request.amount(), clock.instant());
    payments.addFirst(payment);
    while (payments.size() > MAX_RETAINED) {
      payments.pollLast();
    }

    return ResponseEntity.status(HttpStatus.CREATED).body(payment);
  }
}
