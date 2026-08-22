package com.mihir.traffic.ordersservice.web;

import com.mihir.traffic.ordersservice.config.UpstreamBehaviour;
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
 * The orders API: the traffic the platform exists to manage.
 *
 * <p>In-memory state, capped, and lost on restart — this is a load target, not a system of record.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

  /**
   * Cap on retained orders. Without one, a sustained load run is an OutOfMemoryError with extra
   * steps, and the simulator would fail before the platform under test did.
   */
  private static final int MAX_RETAINED = 1_000;

  /**
   * Recent orders, newest first. A deque because the only two operations are "add at the head" and
   * "evict from the tail"; concurrent because Spring serves requests from many threads and this
   * field is shared across all of them.
   */
  private final ConcurrentLinkedDeque<Order> orders = new ConcurrentLinkedDeque<>();

  private final UpstreamBehaviour behaviour;
  private final Clock clock;

  /**
   * Creates the controller.
   *
   * @param behaviour applies the configured latency and error rate
   * @param clock time source, injected so tests are not dependent on the wall clock
   */
  public OrderController(UpstreamBehaviour behaviour, Clock clock) {
    this.behaviour = behaviour;
    this.clock = clock;
  }

  /**
   * Lists recent orders, newest first.
   *
   * @return the retained orders
   */
  @GetMapping
  public List<Order> list() {
    behaviour.applyLatencyAndMaybeFail();
    return List.copyOf(orders);
  }

  /**
   * Places an order.
   *
   * @param request what to order
   * @return the created order
   */
  @PostMapping
  public ResponseEntity<Order> place(@Valid @RequestBody PlaceOrderRequest request) {
    behaviour.applyLatencyAndMaybeFail();

    Order order = new Order(UUID.randomUUID(), request.item(), request.quantity(), clock.instant());
    orders.addFirst(order);
    while (orders.size() > MAX_RETAINED) {
      orders.pollLast();
    }

    return ResponseEntity.status(HttpStatus.CREATED).body(order);
  }
}
