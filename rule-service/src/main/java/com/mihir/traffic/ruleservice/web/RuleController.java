package com.mihir.traffic.ruleservice.web;

import com.mihir.traffic.ruleservice.service.RuleService;
import com.mihir.traffic.ruleservice.web.dto.CreateRuleRequest;
import com.mihir.traffic.ruleservice.web.dto.RulePage;
import com.mihir.traffic.ruleservice.web.dto.RuleResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for throttling rules.
 *
 * <p>HTTP concerns only: binding, validation, status codes, and location headers. Domain decisions
 * live in {@link RuleService}, and there is deliberately no {@code @Transactional} here.
 *
 * <p>Update and the version sub-resources arrive with the versioning branch.
 */
@RestController
public class RuleController {

  private static final String BASE_PATH = "/api/v1/rules";

  private final RuleService ruleService;

  /**
   * Creates the controller.
   *
   * @param ruleService rule lifecycle operations
   */
  public RuleController(RuleService ruleService) {
    this.ruleService = ruleService;
  }

  /**
   * Creates a rule.
   *
   * @param request the rule to create
   * @return 201 with the created rule and its Location header
   */
  @PostMapping(BASE_PATH)
  public ResponseEntity<RuleResponse> create(@Valid @RequestBody CreateRuleRequest request) {
    // Authenticated identity replaces this when auth arrives; recording who
    // changed what is an ADR 0007 requirement the audit service depends on.
    RuleResponse created = ruleService.create(request, "system");
    return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.id())).body(created);
  }

  /**
   * Retrieves a rule.
   *
   * @param id the rule to retrieve
   * @return 200 with the rule
   */
  @GetMapping(BASE_PATH + "/{id}")
  public RuleResponse get(@PathVariable UUID id) {
    return ruleService.get(id);
  }

  /**
   * Lists live rules, cursor-paginated.
   *
   * @param cursor opaque cursor from a previous page, absent for the first page
   * @param size desired page size, clamped to the service's maximum
   * @return 200 with a page of rules
   */
  @GetMapping(BASE_PATH)
  public RulePage list(
      @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
    return ruleService.list(cursor, size);
  }

  /**
   * Soft-deletes a rule. The row and its version history survive, per ADR 0007.
   *
   * @param id the rule to delete
   * @return 204
   */
  @DeleteMapping(BASE_PATH + "/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    ruleService.softDelete(id);
    return ResponseEntity.noContent().build();
  }
}
