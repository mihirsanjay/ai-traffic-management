package com.mihir.traffic.ruleservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable version of a rule's values.
 *
 * <p>Rows here are written once and never updated. A deployment references a specific {@code
 * (ruleId, version)} pair precisely so that the configuration it deployed cannot change underneath
 * it, which is what makes rollback and audit answerable at all (ADR 0007).
 *
 * <p>There are deliberately no setters.
 */
@Entity
@Table(name = "rule_versions")
public class RuleVersion {

  /** Version numbers are per rule and start here, rather than at a global sequence. */
  public static final int FIRST_VERSION = 1;

  @EmbeddedId private RuleVersionId id;

  /** Named {@code limit_value} because {@code limit} is a reserved word in SQL. */
  @Column(name = "limit_value", nullable = false, updatable = false)
  private int limitValue;

  /** Named {@code window_spec} because {@code window} is a reserved word in SQL. */
  @Column(name = "window_spec", nullable = false, updatable = false, length = 20)
  private String windowSpec;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "created_by", nullable = false, updatable = false, length = 100)
  private String createdBy;

  protected RuleVersion() {
    // Required by JPA.
  }

  private RuleVersion(
      RuleVersionId id, int limitValue, String windowSpec, Instant createdAt, String createdBy) {
    this.id = id;
    this.limitValue = limitValue;
    this.windowSpec = windowSpec;
    this.createdAt = createdAt;
    this.createdBy = createdBy;
  }

  /**
   * Creates the first version of a newly created rule.
   *
   * @param ruleId the owning rule
   * @param limitValue requests permitted per window
   * @param windowSpec the window the limit applies over, e.g. {@code 1m}
   * @param createdAt creation timestamp
   * @param createdBy identity of the author
   * @return version 1 of the rule
   */
  public static RuleVersion first(
      UUID ruleId, int limitValue, String windowSpec, Instant createdAt, String createdBy) {
    return new RuleVersion(
        new RuleVersionId(ruleId, FIRST_VERSION), limitValue, windowSpec, createdAt, createdBy);
  }

  public RuleVersionId getId() {
    return id;
  }

  public UUID getRuleId() {
    return id.getRuleId();
  }

  public int getVersion() {
    return id.getVersion();
  }

  public int getLimitValue() {
    return limitValue;
  }

  public String getWindowSpec() {
    return windowSpec;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }
}
