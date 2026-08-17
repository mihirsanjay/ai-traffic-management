package com.mihir.traffic.ruleservice.web.dto;

import com.mihir.traffic.ruleservice.domain.Rule;
import com.mihir.traffic.ruleservice.domain.RuleVersion;
import java.time.Instant;
import java.util.UUID;

/**
 * A rule as returned to callers: its identity joined with the values of its current version.
 *
 * @param id stable rule identity
 * @param service the service the rule throttles
 * @param endpoint the endpoint path the rule throttles
 * @param limit requests permitted per window, from the current version
 * @param window the window the limit applies over, from the current version
 * @param currentVersion the live version number
 * @param createdAt when the rule was first created
 * @param createdBy who created the rule
 */
public record RuleResponse(
    UUID id,
    String service,
    String endpoint,
    int limit,
    String window,
    int currentVersion,
    Instant createdAt,
    String createdBy) {

  /**
   * Projects a rule and its current version into a response.
   *
   * @param rule the rule identity row
   * @param version the version {@code rule.currentVersion} points at
   * @return the response representation
   */
  public static RuleResponse of(Rule rule, RuleVersion version) {
    return new RuleResponse(
        rule.getRuleId(),
        rule.getService(),
        rule.getEndpoint(),
        version.getLimitValue(),
        version.getWindowSpec(),
        rule.getCurrentVersion(),
        rule.getCreatedAt(),
        rule.getCreatedBy());
  }
}
