package com.mihir.traffic.deploymentservice.web;

import com.mihir.traffic.deploymentservice.service.DeploymentService;
import com.mihir.traffic.deploymentservice.web.dto.DeploymentResponse;
import com.mihir.traffic.deploymentservice.web.dto.RollbackRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deployment status and rollback.
 *
 * <p>There is no endpoint to create a deployment. Deployments happen because a rule changed, and
 * the event stream is how that is learned — an endpoint to trigger one directly would be a second
 * path to the same state, with its own ordering and idempotency problems.
 */
@RestController
public class DeploymentController {

  private static final String BASE_PATH = "/api/v1/deployments";

  private final DeploymentService deploymentService;

  /**
   * Creates the controller.
   *
   * @param deploymentService the business logic
   */
  public DeploymentController(DeploymentService deploymentService) {
    this.deploymentService = deploymentService;
  }

  /**
   * Retrieves one deployment.
   *
   * @param id the deployment to read
   * @return 200 with its current state
   */
  @GetMapping(BASE_PATH + "/{id}")
  public DeploymentResponse get(@PathVariable UUID id) {
    return deploymentService.get(id);
  }

  /**
   * Lists a rule's deployment history, newest first.
   *
   * @param ruleId the rule whose history to read
   * @param size desired page size, clamped server-side
   * @return 200 with that rule's deployments
   */
  @GetMapping(BASE_PATH)
  public List<DeploymentResponse> history(
      @RequestParam UUID ruleId, @RequestParam(required = false) Integer size) {
    return deploymentService.history(ruleId, size);
  }

  /**
   * Restores a previously deployed rule version.
   *
   * @param ruleId the rule to roll back
   * @param request the version to restore
   * @return 201 with the new deployment, since a rollback appends rather than mutating
   */
  @PostMapping(BASE_PATH + "/{ruleId}/rollback")
  public ResponseEntity<DeploymentResponse> rollback(
      @PathVariable UUID ruleId, @Valid @RequestBody RollbackRequest request) {
    DeploymentResponse deployment = deploymentService.rollback(ruleId, request.toVersion());
    return ResponseEntity.created(java.net.URI.create(BASE_PATH + "/" + deployment.id()))
        .body(deployment);
  }
}
