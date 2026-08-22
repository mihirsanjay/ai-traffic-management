package com.mihir.traffic.ordersservice.config;

/** Signals that this request was chosen to fail by the configured error rate. */
public class SimulatedFailureException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why the request failed
   */
  public SimulatedFailureException(String message) {
    super(message);
  }
}
