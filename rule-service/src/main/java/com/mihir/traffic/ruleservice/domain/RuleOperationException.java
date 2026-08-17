package com.mihir.traffic.ruleservice.domain;

/**
 * Carries a {@link RuleError} from the service layer to the web layer, where it is rendered as an
 * RFC 7807 {@code ProblemDetail}.
 *
 * <p>The error itself is the sealed {@link RuleError} payload; this type exists only as the
 * transport, so exhaustiveness checking still applies at the point the response is built.
 */
public class RuleOperationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient RuleError error;

  /**
   * Wraps a domain error for propagation.
   *
   * @param error the domain outcome that prevented the operation
   */
  public RuleOperationException(RuleError error) {
    super(error.getClass().getSimpleName());
    this.error = error;
  }

  public RuleError getError() {
    return error;
  }
}
