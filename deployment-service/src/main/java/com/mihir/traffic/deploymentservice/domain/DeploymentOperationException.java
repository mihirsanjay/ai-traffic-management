package com.mihir.traffic.deploymentservice.domain;

/**
 * Carries a {@link DeploymentError} out to the web layer.
 *
 * <p>The exception is transport; the error is the value. Runtime exceptions signal programming
 * errors, while an expected outcome like "that version was never deployed" is a domain result that
 * happens to need unwinding the call stack.
 */
public class DeploymentOperationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient DeploymentError error;

  /**
   * Creates the exception.
   *
   * @param error what went wrong
   */
  public DeploymentOperationException(DeploymentError error) {
    super(error.toString());
    this.error = error;
  }

  public DeploymentError getError() {
    return error;
  }
}
