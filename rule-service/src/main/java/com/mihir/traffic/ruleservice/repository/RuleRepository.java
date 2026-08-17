package com.mihir.traffic.ruleservice.repository;

import com.mihir.traffic.ruleservice.domain.Rule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for rule identities. Soft-deleted rules are excluded from every query here. */
public interface RuleRepository extends JpaRepository<Rule, UUID> {

  /**
   * Finds a live rule by identity.
   *
   * @param ruleId the rule to look up
   * @return the rule, or empty if absent or soft-deleted
   */
  @Query("SELECT r FROM Rule r WHERE r.ruleId = :ruleId AND r.deletedAt IS NULL")
  Optional<Rule> findLiveById(@Param("ruleId") UUID ruleId);

  /**
   * Checks whether a live rule already targets this service and endpoint. Mirrors the partial
   * unique index, which is the actual guarantee; this exists so the common case returns a clean 409
   * rather than surfacing a constraint violation.
   *
   * @param service the service to check
   * @param endpoint the endpoint to check
   * @return true if a live rule already covers this target
   */
  @Query(
      "SELECT COUNT(r) > 0 FROM Rule r "
          + "WHERE r.service = :service AND r.endpoint = :endpoint AND r.deletedAt IS NULL")
  boolean existsLiveByServiceAndEndpoint(
      @Param("service") String service, @Param("endpoint") String endpoint);

  /**
   * First page of live rules, ordered by the stable {@code (createdAt, ruleId)} pair the cursor
   * encodes. Ordering by creation time alone would be ambiguous for rules created in the same
   * instant, which is precisely when a cursor would skip or repeat a row.
   *
   * @param limit maximum rows to return
   * @return the first page of live rules
   */
  @Query("SELECT r FROM Rule r WHERE r.deletedAt IS NULL ORDER BY r.createdAt ASC, r.ruleId ASC")
  List<Rule> findFirstLivePage(Limit limit);

  /**
   * Page of live rules strictly after the supplied cursor position.
   *
   * @param afterCreatedAt creation timestamp from the cursor
   * @param afterRuleId rule identity from the cursor, breaking ties on the timestamp
   * @param limit maximum rows to return
   * @return the next page of live rules
   */
  @Query(
      "SELECT r FROM Rule r WHERE r.deletedAt IS NULL "
          + "AND (r.createdAt > :afterCreatedAt "
          + "     OR (r.createdAt = :afterCreatedAt AND r.ruleId > :afterRuleId)) "
          + "ORDER BY r.createdAt ASC, r.ruleId ASC")
  List<Rule> findLivePageAfter(
      @Param("afterCreatedAt") Instant afterCreatedAt,
      @Param("afterRuleId") UUID afterRuleId,
      Limit limit);
}
