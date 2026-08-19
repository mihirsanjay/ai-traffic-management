package com.mihir.traffic.ruleservice.web;

import com.mihir.traffic.ruleservice.domain.RuleError;
import com.mihir.traffic.ruleservice.domain.RuleOperationException;
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
 * Renders every error as an RFC 7807 {@code ProblemDetail}, so one error shape holds across the
 * platform.
 *
 * <p>Internal detail never reaches the caller. Unexpected failures return a correlation ID and
 * nothing else; the stack trace goes to the logs, where it belongs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static final String PROBLEM_BASE = "https://errors.mihir.traffic/rule-service/";
  private static final String CORRELATION_ID = "correlationId";

  /**
   * Maps an expected domain outcome to its HTTP representation.
   *
   * @param exception the carried domain error
   * @return the problem detail for the caller
   */
  @ExceptionHandler(RuleOperationException.class)
  public ProblemDetail handleRuleOperation(RuleOperationException exception) {
    // Switch over a sealed type with no default branch: adding a RuleError
    // variant must fail compilation here rather than silently falling through
    // to a generic 500. Checkstyle's MissingSwitchDefault is suppressed for
    // exactly that reason - see docs/coding-standards.md on exhaustiveness.
    @SuppressWarnings("checkstyle:MissingSwitchDefault")
    ProblemDetail problem =
        switch (exception.getError()) {
          case RuleError.RuleNotFound e ->
              problem(
                  HttpStatus.NOT_FOUND,
                  "rule-not-found",
                  "Rule not found",
                  "No live rule exists with id " + e.ruleId() + ".");
          case RuleError.DuplicateRule e ->
              problem(
                  HttpStatus.CONFLICT,
                  "duplicate-rule",
                  "Rule already exists",
                  "A live rule already throttles " + e.service() + " " + e.endpoint() + ".");
          case RuleError.MalformedCursor e ->
              problem(
                  HttpStatus.BAD_REQUEST,
                  "malformed-cursor",
                  "Malformed pagination cursor",
                  "The cursor is not one this service issued.");
          // 409, never a masked success and never a 500: the update genuinely
          // did not happen, and retrying it is a reasonable thing for the
          // caller to do (ADR 0008).
          case RuleError.VersionConflict e ->
              problem(
                  HttpStatus.CONFLICT,
                  "version-conflict",
                  "Concurrent rule update",
                  "The rule was updated concurrently and the change could not be applied after "
                      + e.attempts()
                      + " attempt(s). Retry the request.");
        };
    return problem;
  }

  /**
   * Maps Bean Validation failures to 400 with the offending fields named.
   *
   * @param exception the validation failure
   * @return the problem detail for the caller
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
    String fields =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .sorted()
            .reduce((a, b) -> a + "; " + b)
            .orElse("Request body failed validation.");
    return problem(HttpStatus.BAD_REQUEST, "validation-failed", "Invalid request", fields);
  }

  /**
   * Maps an unparseable request body to 400 rather than 500 - a malformed payload is the caller's
   * error, not ours.
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
   * Maps a path variable of the wrong shape - most often a non-UUID rule id - to 400.
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
