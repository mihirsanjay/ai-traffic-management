package com.mihir.traffic.deploymentservice.web;

import com.mihir.traffic.deploymentservice.domain.DeploymentError;
import com.mihir.traffic.deploymentservice.domain.DeploymentOperationException;
import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns failures into RFC 7807 problem details.
 *
 * <p>Deliberately a copy of rule-service's rather than something shared. A shared
 * {@code @RestControllerAdvice} would have to live in {@code common}, which would then need a
 * servlet stack on its classpath — and {@code common} is imported by every service, including the
 * Phase 3 simulators, which are required to have no platform dependency at all. Duplicating forty
 * lines is the cheaper of the two costs, and ADR 0003 is explicit that {@code common} holds event
 * schemas and shared error types only.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static final String PROBLEM_BASE = "https://errors.mihir.traffic/deployment-service/";

  private static final String CORRELATION_ID = "correlationId";

  /**
   * Maps an expected deployment failure to its status code.
   *
   * @param exception the domain failure
   * @return the problem detail for the caller
   */
  @ExceptionHandler(DeploymentOperationException.class)
  public ProblemDetail handleDeploymentOperation(DeploymentOperationException exception) {
    // Switch over a sealed type with no default branch: adding a DeploymentError
    // variant must fail compilation here rather than silently falling through to
    // a generic 500. Checkstyle's MissingSwitchDefault is suppressed for exactly
    // that reason - see docs/coding-standards.md on exhaustiveness.
    @SuppressWarnings("checkstyle:MissingSwitchDefault")
    ProblemDetail problem =
        switch (exception.getError()) {
          case DeploymentError.DeploymentNotFound e ->
              problem(
                  HttpStatus.NOT_FOUND,
                  "deployment-not-found",
                  "Deployment not found",
                  "No deployment exists with id " + e.deploymentId() + ".");
          case DeploymentError.NoDeploymentsForRule e ->
              problem(
                  HttpStatus.NOT_FOUND,
                  "no-deployments-for-rule",
                  "Rule has never been deployed",
                  "Rule " + e.ruleId() + " has no deployment history to roll back through.");
          case DeploymentError.VersionNeverDeployed e ->
              problem(
                  HttpStatus.NOT_FOUND,
                  "version-never-deployed",
                  "Version was never deployed",
                  "Version "
                      + e.ruleVersion()
                      + " of rule "
                      + e.ruleId()
                      + " was never successfully deployed, so there is nothing to restore.");
          case DeploymentError.VersionAlreadyLive e ->
              problem(
                  HttpStatus.CONFLICT,
                  "version-already-live",
                  "Version is already live",
                  "Version "
                      + e.ruleVersion()
                      + " of rule "
                      + e.ruleId()
                      + " is already deployed.");
        };
    return problem;
  }

  /**
   * Maps Bean Validation failures on a request body to 400.
   *
   * @param exception the validation failure
   * @return the problem detail for the caller
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "invalid-request",
        "Invalid request",
        "The request body failed validation.");
  }

  /**
   * Maps an unparseable body to 400.
   *
   * @param exception the parse failure
   * @return the problem detail for the caller
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "malformed-request-body",
        "Malformed request body",
        "The request body could not be parsed as JSON.");
  }

  /**
   * Maps a path variable of the wrong shape - most often a non-UUID id - to 400.
   *
   * @param exception the conversion failure
   * @return the problem detail for the caller
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "invalid-path-parameter",
        "Invalid path parameter",
        "Parameter '" + exception.getName() + "' is not in the expected format.");
  }

  /**
   * Last resort for genuinely unexpected failures.
   *
   * @param exception the unhandled failure
   * @return an opaque 500 carrying only a correlation ID
   */
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception exception) {
    // Generated here rather than read back out of the response, so the value
    // logged and the value returned are provably the same string.
    String correlationId = UUID.randomUUID().toString();
    ProblemDetail problem =
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "Internal server error",
            "The request could not be completed. Quote the correlation ID when reporting this.",
            correlationId);
    // ERROR is right here: this reached the caller as a 500 and a human must
    // look at it. The trace stays in the logs and never goes over the wire.
    LOG.error("Unhandled exception serving request, correlationId={}", correlationId, exception);
    return problem;
  }

  private static ProblemDetail problem(
      HttpStatus status, String type, String title, String detail) {
    return problem(status, type, title, detail, UUID.randomUUID().toString());
  }

  private static ProblemDetail problem(
      HttpStatus status, String type, String title, String detail, String correlationId) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(PROBLEM_BASE + type));
    problem.setTitle(title);
    problem.setProperty(CORRELATION_ID, correlationId);
    return problem;
  }
}
