package com.mihir.traffic.deploymentservice.repository;

import com.mihir.traffic.deploymentservice.domain.Deployment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link Deployment}. */
public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

  /**
   * Returns a rule's deployment history, newest first.
   *
   * @param ruleId the rule
   * @param limit maximum rows
   * @return deployments for that rule, newest first
   */
  @Query("SELECT d FROM Deployment d WHERE d.ruleId = :ruleId ORDER BY d.createdAt DESC")
  List<Deployment> findHistory(@Param("ruleId") UUID ruleId, Limit limit);

  /**
   * Returns the most recent successful deployment of a specific rule version.
   *
   * <p>Rollback reads this: the values it needs are the ones that were actually applied last time
   * that version was live, and they live on the deployment row rather than being fetched from the
   * rule store.
   *
   * @param ruleId the rule
   * @param ruleVersion the version to look for
   * @return that deployment, if the version was ever successfully deployed
   */
  @Query(
      """
      SELECT d FROM Deployment d
      WHERE d.ruleId = :ruleId AND d.ruleVersion = :ruleVersion
        AND d.status = com.mihir.traffic.deploymentservice.domain.DeploymentStatus.SUCCEEDED
      ORDER BY d.createdAt DESC
      LIMIT 1
      """)
  Optional<Deployment> findLastSuccessfulAtVersion(
      @Param("ruleId") UUID ruleId, @Param("ruleVersion") int ruleVersion);

  /**
   * Returns the rule's most recent deployment, whatever its outcome.
   *
   * @param ruleId the rule
   * @return the newest deployment for that rule
   */
  @Query("SELECT d FROM Deployment d WHERE d.ruleId = :ruleId ORDER BY d.createdAt DESC LIMIT 1")
  Optional<Deployment> findLatest(@Param("ruleId") UUID ruleId);
}
