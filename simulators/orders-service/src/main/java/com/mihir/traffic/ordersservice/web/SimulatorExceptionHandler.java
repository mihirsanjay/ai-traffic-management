package com.mihir.traffic.ordersservice.web;

import com.mihir.traffic.ordersservice.config.SimulatedFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a simulated failure into a 500 with an RFC 7807 body.
 *
 * <p>Deliberately not the platform's shared handler: this service does not depend on `common`, and
 * matching the platform's error shape by convention rather than by shared code is the point.
 */
@RestControllerAdvice
public class SimulatorExceptionHandler {

  /**
   * Handles a deliberately induced upstream failure.
   *
   * @param e the simulated failure
   * @return a 500 problem detail
   */
  @ExceptionHandler(SimulatedFailureException.class)
  public ProblemDetail handleSimulatedFailure(SimulatedFailureException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    problem.setTitle("Upstream failure");
    return problem;
  }
}
