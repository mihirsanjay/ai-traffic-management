package com.mihir.traffic.ruleservice.domain;

import java.util.UUID;

/**
 * Expected failures of rule operations.
 *
 * <p>Sealed so the compiler enforces exhaustive handling: adding a variant produces a compile error
 * at every site that must change, which is exactly what should happen when a new failure mode
 * appears. These are domain outcomes rather than exceptional events - "rule not found" is an
 * ordinary answer to a lookup, not a crash.
 */
public sealed interface RuleError {

  /**
   * No live rule exists with the requested identity.
   *
   * @param ruleId the identity that was looked up
   */
  record RuleNotFound(UUID ruleId) implements RuleError {}

  /**
   * A live rule already throttles this service and endpoint.
   *
   * @param service the conflicting service
   * @param endpoint the conflicting endpoint
   */
  record DuplicateRule(String service, String endpoint) implements RuleError {}

  /**
   * The supplied pagination cursor could not be decoded.
   *
   * @param cursor the rejected cursor value
   */
  record MalformedCursor(String cursor) implements RuleError {}
}
