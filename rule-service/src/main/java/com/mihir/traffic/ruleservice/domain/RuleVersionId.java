package com.mihir.traffic.ruleservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key {@code (ruleId, version)} for {@link RuleVersion}.
 *
 * <p>This key is what makes duplicate version numbers physically impossible rather than merely
 * unlikely: the database rejects a second insert of the same pair regardless of what the
 * application believes.
 *
 * <p>A class rather than a record because JPA requires a no-arg constructor on an
 * {@code @Embeddable} identifier.
 */
@Embeddable
public class RuleVersionId implements Serializable {

  private static final long serialVersionUID = 1L;

  @Column(name = "rule_id", nullable = false, updatable = false)
  private UUID ruleId;

  @Column(name = "version", nullable = false, updatable = false)
  private int version;

  protected RuleVersionId() {
    // Required by JPA.
  }

  public RuleVersionId(UUID ruleId, int version) {
    this.ruleId = ruleId;
    this.version = version;
  }

  public UUID getRuleId() {
    return ruleId;
  }

  public int getVersion() {
    return version;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof RuleVersionId that)) {
      return false;
    }
    return version == that.version && Objects.equals(ruleId, that.ruleId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ruleId, version);
  }
}
