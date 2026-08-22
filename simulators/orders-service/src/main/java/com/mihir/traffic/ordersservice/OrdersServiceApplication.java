package com.mihir.traffic.ordersservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A simulated order-taking service.
 *
 * <p>Deliberately unaware that a traffic-management platform exists. It serves requests and knows
 * nothing about rules, throttling, or the proxy in front of it — a request that Envoy rejected
 * never arrives here at all, and this service cannot tell that from a request that was never sent.
 *
 * <p>That ignorance is the requirement, not a simplification. A test environment that cooperates
 * with the system under test stops testing it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrdersServiceApplication {

  protected OrdersServiceApplication() {
    // Spring instantiates this class; it is not meant to be constructed directly.
  }

  /**
   * Starts the service.
   *
   * @param args standard Spring Boot arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(OrdersServiceApplication.class, args);
  }
}
