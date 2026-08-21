package com.mihir.traffic.deploymentservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mihir.traffic.common.event.RuleChangedPayload;
import com.mihir.traffic.deploymentservice.AbstractIntegrationTest;
import com.mihir.traffic.deploymentservice.domain.DeploymentStatus;
import com.mihir.traffic.deploymentservice.repository.DeploymentRepository;
import com.mihir.traffic.deploymentservice.repository.ProcessedEventRepository;
import com.mihir.traffic.deploymentservice.service.DeploymentService;
import com.mihir.traffic.deploymentservice.web.dto.DeploymentResponse;
import com.mihir.traffic.deploymentservice.web.dto.RollbackRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** The deployment API end to end, over real HTTP against a real database. */
class DeploymentControllerIntegrationTest extends AbstractIntegrationTest {

  private static final String BASE = "/api/v1/deployments";

  @Autowired private DeploymentService deploymentService;
  @Autowired private DeploymentRepository deploymentRepository;
  @Autowired private ProcessedEventRepository processedEventRepository;

  @BeforeEach
  void clearTables() {
    deploymentRepository.deleteAll();
    processedEventRepository.deleteAll();
  }

  @Test
  @DisplayName("a deployment can be read back by id")
  void aDeploymentCanBeReadBackById() {
    UUID ruleId = UUID.randomUUID();
    deploymentService.applyFromEvent(UUID.randomUUID(), payload(ruleId, 1, 1000));
    UUID deploymentId = deploymentRepository.findAll().get(0).getDeploymentId();

    ResponseEntity<DeploymentResponse> response =
        restTemplate().getForEntity(url(BASE + "/" + deploymentId), DeploymentResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().ruleId()).isEqualTo(ruleId);
    assertThat(response.getBody().status()).isEqualTo(DeploymentStatus.SUCCEEDED);
  }

  @Test
  @DisplayName("an unknown deployment is a 404 problem detail, not a 500")
  void anUnknownDeploymentIsAProblemDetail() {
    ResponseEntity<ProblemDetail> response =
        restTemplate().getForEntity(url(BASE + "/" + UUID.randomUUID()), ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getTitle()).isEqualTo("Deployment not found");
  }

  @Test
  @DisplayName("rollback restores a prior version by appending a new deployment")
  void rollbackAppendsADeploymentRestoringThePriorVersion() {
    UUID ruleId = UUID.randomUUID();
    deploymentService.applyFromEvent(UUID.randomUUID(), payload(ruleId, 1, 1000));
    deploymentService.applyFromEvent(UUID.randomUUID(), payload(ruleId, 2, 50));

    ResponseEntity<DeploymentResponse> response =
        restTemplate()
            .postForEntity(
                url(BASE + "/" + ruleId + "/rollback"),
                new RollbackRequest(1),
                DeploymentResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().ruleVersion()).isEqualTo(1);
    assertThat(response.getBody().limit()).isEqualTo(1000);

    // Appended, not rewritten. The history is meant to read as what actually
    // happened - v1, v2, then v1 again - rather than pretending v2 never was.
    assertThat(deploymentRepository.count()).isEqualTo(3);
  }

  @Test
  @DisplayName("rolling back to the live version is a conflict")
  void rollingBackToTheLiveVersionIsAConflict() {
    UUID ruleId = UUID.randomUUID();
    deploymentService.applyFromEvent(UUID.randomUUID(), payload(ruleId, 1, 1000));

    ResponseEntity<ProblemDetail> response =
        restTemplate()
            .postForEntity(
                url(BASE + "/" + ruleId + "/rollback"),
                new RollbackRequest(1),
                ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  @DisplayName("rolling back to a version that was never deployed is a 404")
  void rollingBackToAVersionNeverDeployedIsNotFound() {
    UUID ruleId = UUID.randomUUID();
    deploymentService.applyFromEvent(UUID.randomUUID(), payload(ruleId, 1, 1000));

    ResponseEntity<ProblemDetail> response =
        restTemplate()
            .postForEntity(
                url(BASE + "/" + ruleId + "/rollback"),
                new RollbackRequest(7),
                ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getTitle()).isEqualTo("Version was never deployed");
  }

  @Test
  @DisplayName("history returns a rule's deployments newest first")
  void historyReturnsDeploymentsNewestFirst() {
    UUID ruleId = UUID.randomUUID();
    deploymentService.applyFromEvent(UUID.randomUUID(), payload(ruleId, 1, 1000));
    deploymentService.applyFromEvent(UUID.randomUUID(), payload(ruleId, 2, 50));

    ResponseEntity<DeploymentResponse[]> response =
        restTemplate().getForEntity(url(BASE + "?ruleId=" + ruleId), DeploymentResponse[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).hasSize(2);
    assertThat(response.getBody()[0].ruleVersion()).isEqualTo(2);
  }

  private static RuleChangedPayload payload(UUID ruleId, int version, int limit) {
    return new RuleChangedPayload(ruleId, version, "orders", "/orders", limit, "1m", "alice");
  }
}
