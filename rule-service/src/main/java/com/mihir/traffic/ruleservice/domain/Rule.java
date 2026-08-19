package com.mihir.traffic.ruleservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A throttling rule's stable identity and a pointer to its live version.
 *
 * <p>Per ADR 0007 this row carries no rule <em>values</em> - the limit and window live in {@link
 * RuleVersion} rows, which are immutable once written. Changing a rule appends a version and moves
 * {@code currentVersion}; it never overwrites a stored value.
 *
 * <p>A class rather than a record: JPA entities need a no-arg constructor and mutable fields.
 */
@Entity
@Table(name = "rules")
public class Rule {

  @Id
  @Column(name = "rule_id", nullable = false, updatable = false)
  private UUID ruleId;

  @Column(name = "service", nullable = false, length = 100)
  private String service;

  @Column(name = "endpoint", nullable = false, length = 200)
  private String endpoint;

  @Column(name = "current_version", nullable = false)
  private int currentVersion;

  /**
   * Optimistic-locking counter (ADR 0008). Hibernate appends this to the WHERE clause on update, so
   * a writer whose read is stale affects zero rows and fails rather than silently overwriting a
   * concurrent change.
   */
  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  /** Tombstone. Non-null means soft-deleted; the row and its history survive deliberately. */
  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "created_by", nullable = false, updatable = false, length = 100)
  private String createdBy;

  protected Rule() {
    // Required by JPA.
  }

  private Rule(UUID ruleId, String service, String endpoint, Instant createdAt, String createdBy) {
    this.ruleId = ruleId;
    this.service = service;
    this.endpoint = endpoint;
    this.currentVersion = RuleVersion.FIRST_VERSION;
    this.createdAt = createdAt;
    this.createdBy = createdBy;
  }

  /**
   * Creates a rule at version 1. The caller is responsible for persisting the matching {@link
   * RuleVersion} in the same transaction.
   *
   * @param service the service the rule throttles
   * @param endpoint the endpoint path the rule throttles
   * @param createdAt creation timestamp
   * @param createdBy identity of the author
   * @return a new rule pointing at version 1
   */
  public static Rule create(String service, String endpoint, Instant createdAt, String createdBy) {
    return new Rule(UUID.randomUUID(), service, endpoint, createdAt, createdBy);
  }

  /**
   * Moves the live-version pointer forward to a newly appended version.
   *
   * <p>The caller is responsible for having persisted the matching {@link RuleVersion} in the same
   * transaction. Only a forward move by exactly one is accepted: anything else means the caller
   * computed the next version from a stale read, and silently accepting it would produce exactly
   * the lost update ADR 0008 exists to prevent.
   *
   * @param version the version number now live
   * @throws IllegalArgumentException if {@code version} does not immediately succeed the current
   *     one
   */
  public void applyVersion(int version) {
    if (version != currentVersion + 1) {
      throw new IllegalArgumentException(
          "Version must advance by exactly one: current="
              + currentVersion
              + ", supplied="
              + version);
    }
    this.currentVersion = version;
  }

  /** Marks this rule deleted without destroying it, per ADR 0007. */
  public void softDelete(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  public UUID getRuleId() {
    return ruleId;
  }

  public String getService() {
    return service;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public int getCurrentVersion() {
    return currentVersion;
  }

  public long getLockVersion() {
    return lockVersion;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }
}
