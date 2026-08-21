package com.mihir.traffic.deploymentservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to apply a rule version to the data plane.
 *
 * <p>Append-only in the same spirit as ADR 0007's rule versions: a rollback writes a <em>new</em>
 * row targeting the older version rather than deleting or rewriting the newer one. The history then
 * reads honestly — "v3, then v4, then v3 again" is what actually happened, and a deployment record
 * never becomes a lie about the past.
 *
 * <p>The rule's targeting and values are copied here from the event rather than looked up. This
 * service cannot read the rule store, and copying them means the whole desired state of the data
 * plane is assemblable from this table alone.
 */
@Entity
@Table(name = "deployments")
public class Deployment {

  @Id
  @Column(name = "deployment_id", nullable = false, updatable = false)
  private UUID deploymentId;

  @Column(name = "rule_id", nullable = false, updatable = false)
  private UUID ruleId;

  @Column(name = "rule_version", nullable = false, updatable = false)
  private int ruleVersion;

  @Column(name = "service", nullable = false, updatable = false, length = 100)
  private String service;

  @Column(name = "endpoint", nullable = false, updatable = false, length = 200)
  private String endpoint;

  /** Null for a deletion: the rule no longer has a limit. */
  @Column(name = "limit_value", updatable = false)
  private Integer limitValue;

  /** Null for a deletion. */
  @Column(name = "window_spec", updatable = false, length = 20)
  private String windowSpec;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private DeploymentStatus status;

  @Column(name = "detail")
  private String detail;

  @Column(name = "config_version")
  private Long configVersion;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Deployment() {
    // JPA.
  }

  private Deployment(
      UUID ruleId,
      int ruleVersion,
      String service,
      String endpoint,
      Integer limitValue,
      String windowSpec,
      Instant createdAt) {
    this.deploymentId = UUID.randomUUID();
    this.ruleId = ruleId;
    this.ruleVersion = ruleVersion;
    this.service = service;
    this.endpoint = endpoint;
    this.limitValue = limitValue;
    this.windowSpec = windowSpec;
    this.status = DeploymentStatus.PENDING;
    this.createdAt = createdAt;
  }

  /**
   * Records a new deployment attempt, not yet applied.
   *
   * @param ruleId the rule being deployed
   * @param ruleVersion the specific version being deployed
   * @param service the service the rule targets
   * @param endpoint the endpoint the rule targets
   * @param limitValue requests per window, or null for a deletion
   * @param windowSpec the window, or null for a deletion
   * @param createdAt when the attempt started
   * @return a pending deployment
   */
  public static Deployment pending(
      UUID ruleId,
      int ruleVersion,
      String service,
      String endpoint,
      Integer limitValue,
      String windowSpec,
      Instant createdAt) {
    return new Deployment(
        ruleId, ruleVersion, service, endpoint, limitValue, windowSpec, createdAt);
  }

  /**
   * Marks the attempt applied.
   *
   * @param configVersion the configuration version the data plane confirmed, or null while the
   *     config writer is still a no-op
   */
  public void succeeded(Long configVersion) {
    this.status = DeploymentStatus.SUCCEEDED;
    this.configVersion = configVersion;
    this.detail = null;
  }

  /**
   * Marks the attempt failed.
   *
   * @param detail why it failed; this is what an operator reads first
   */
  public void failed(String detail) {
    this.status = DeploymentStatus.FAILED;
    this.detail = detail;
  }

  /** Whether this deployment is a rule deletion rather than a limit to enforce. */
  public boolean isDeletion() {
    return limitValue == null;
  }

  public UUID getDeploymentId() {
    return deploymentId;
  }

  public UUID getRuleId() {
    return ruleId;
  }

  public int getRuleVersion() {
    return ruleVersion;
  }

  public String getService() {
    return service;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public Integer getLimitValue() {
    return limitValue;
  }

  public String getWindowSpec() {
    return windowSpec;
  }

  public DeploymentStatus getStatus() {
    return status;
  }

  public String getDetail() {
    return detail;
  }

  public Long getConfigVersion() {
    return configVersion;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
