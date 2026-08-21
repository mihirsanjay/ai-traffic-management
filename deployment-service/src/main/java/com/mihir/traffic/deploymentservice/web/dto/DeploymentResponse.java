package com.mihir.traffic.deploymentservice.web.dto;

import com.mihir.traffic.deploymentservice.domain.Deployment;
import com.mihir.traffic.deploymentservice.domain.DeploymentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A deployment as the API reports it.
 *
 * @param id the deployment
 * @param ruleId the rule deployed
 * @param ruleVersion the specific version deployed
 * @param service the service the rule targets
 * @param endpoint the endpoint the rule targets
 * @param limit requests per window, or null if this deployment removes a rule
 * @param window the window, or null if this deployment removes a rule
 * @param status where the attempt got to
 * @param detail why a failure failed; null otherwise
 * @param configVersion the configuration version the data plane confirmed, if any
 * @param createdAt when the attempt started
 */
public record DeploymentResponse(
    UUID id,
    UUID ruleId,
    int ruleVersion,
    String service,
    String endpoint,
    Integer limit,
    String window,
    DeploymentStatus status,
    String detail,
    Long configVersion,
    Instant createdAt) {

  /**
   * Projects a stored deployment.
   *
   * @param deployment the stored record
   * @return its API representation
   */
  public static DeploymentResponse of(Deployment deployment) {
    return new DeploymentResponse(
        deployment.getDeploymentId(),
        deployment.getRuleId(),
        deployment.getRuleVersion(),
        deployment.getService(),
        deployment.getEndpoint(),
        deployment.getLimitValue(),
        deployment.getWindowSpec(),
        deployment.getStatus(),
        deployment.getDetail(),
        deployment.getConfigVersion(),
        deployment.getCreatedAt());
  }
}
