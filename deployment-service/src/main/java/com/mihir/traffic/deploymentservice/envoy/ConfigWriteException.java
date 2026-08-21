package com.mihir.traffic.deploymentservice.envoy;

/**
 * The data plane could not be given the configuration.
 *
 * <p>A named type rather than letting arbitrary runtime exceptions escape, so callers can catch
 * exactly this and record the deployment as failed. Catching broad types is banned by the project's
 * Checkstyle rules for good reason: it hides what is actually being handled, and here what is being
 * handled is specifically "the apply did not work", not "something went wrong somewhere".
 *
 * <p>Phase 3's writer throws this when Envoy rejects a configuration or does not confirm it within
 * the timeout.
 */
public class ConfigWriteException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what could not be applied, and why
   */
  public ConfigWriteException(String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message what could not be applied, and why
   * @param cause the underlying failure
   */
  public ConfigWriteException(String message, Throwable cause) {
    super(message, cause);
  }
}
