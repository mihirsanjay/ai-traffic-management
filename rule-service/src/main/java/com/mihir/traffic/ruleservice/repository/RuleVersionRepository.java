package com.mihir.traffic.ruleservice.repository;

import com.mihir.traffic.ruleservice.domain.RuleVersion;
import com.mihir.traffic.ruleservice.domain.RuleVersionId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for immutable rule versions.
 *
 * <p>Write-once by design: there is no update method here, and there should never be one. Per ADR
 * 0007 a change appends a version rather than mutating an existing row.
 */
public interface RuleVersionRepository extends JpaRepository<RuleVersion, RuleVersionId> {

  /**
   * Loads the current versions of several rules at once, so listing a page of rules does not issue
   * one query per row.
   *
   * @param ids the {@code (ruleId, version)} pairs to load
   * @return the matching versions, in unspecified order
   */
  @Query("SELECT v FROM RuleVersion v WHERE v.id IN :ids")
  List<RuleVersion> findAllByIdIn(@Param("ids") Collection<RuleVersionId> ids);

  /**
   * All versions of a rule, newest first. The versions sub-resource in the next phase-1 branch
   * reads through this.
   *
   * @param ruleId the rule whose history to load
   * @return every stored version of the rule
   */
  @Query("SELECT v FROM RuleVersion v WHERE v.id.ruleId = :ruleId ORDER BY v.id.version DESC")
  List<RuleVersion> findHistory(@Param("ruleId") UUID ruleId);
}
