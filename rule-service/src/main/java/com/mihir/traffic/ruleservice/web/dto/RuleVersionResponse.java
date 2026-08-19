package com.mihir.traffic.ruleservice.web.dto;

import com.mihir.traffic.ruleservice.domain.RuleVersion;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable version of a rule, as returned by the versions sub-resource.
 *
 * <p>Distinct from {@link RuleResponse}, which projects a rule's <em>current</em> values onto its
 * identity. This one describes a specific historical version and so carries no {@code
 * currentVersion} field - asking "which version is live" of a historical row is a category error.
 *
 * @param ruleId the owning rule
 * @param version this version's number, unique per rule
 * @param limit requests permitted per window in this version
 * @param window the window this version's limit applies over
 * @param createdAt when this version was written
 * @param createdBy who authored this version
 */
public record RuleVersionResponse(
    UUID ruleId, int version, int limit, String window, Instant createdAt, String createdBy) {

  /**
   * Projects a stored version into a response.
   *
   * @param version the stored version row
   * @return the response representation
   */
  public static RuleVersionResponse of(RuleVersion version) {
    return new RuleVersionResponse(
        version.getRuleId(),
        version.getVersion(),
        version.getLimitValue(),
        version.getWindowSpec(),
        version.getCreatedAt(),
        version.getCreatedBy());
  }
}
