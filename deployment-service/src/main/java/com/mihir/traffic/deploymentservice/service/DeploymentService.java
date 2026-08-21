package com.mihir.traffic.deploymentservice.service;

import com.mihir.traffic.common.event.RuleChangedPayload;
import com.mihir.traffic.deploymentservice.domain.Deployment;
import com.mihir.traffic.deploymentservice.domain.DeploymentError;
import com.mihir.traffic.deploymentservice.domain.DeploymentOperationException;
import com.mihir.traffic.deploymentservice.domain.ProcessedEvent;
import com.mihir.traffic.deploymentservice.envoy.ConfigWriteException;
import com.mihir.traffic.deploymentservice.envoy.ConfigWriter;
import com.mihir.traffic.deploymentservice.repository.DeploymentRepository;
import com.mihir.traffic.deploymentservice.repository.ProcessedEventRepository;
import com.mihir.traffic.deploymentservice.web.dto.DeploymentResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deployment lifecycle: applying a rule version, reporting status, and rolling back.
 *
 * <p>Transaction boundaries live here rather than in the controller or the consumer, and the data
 * plane is never touched inside one — a remote call inside a transaction holds a database
 * connection for the duration of someone else's latency.
 */
@Service
public class DeploymentService {

  private static final Logger LOG = LoggerFactory.getLogger(DeploymentService.class);

  /** Ceiling on history page size. An unbounded list endpoint is an outage waiting for data. */
  public static final int MAX_PAGE_SIZE = 100;

  /** History page size used when a caller does not ask for one. */
  public static final int DEFAULT_PAGE_SIZE = 20;

  private final DeploymentRepository deploymentRepository;
  private final ProcessedEventRepository processedEventRepository;
  private final ConfigWriter configWriter;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param deploymentRepository persistence for deployment records
   * @param processedEventRepository the idempotency ledger
   * @param configWriter installs configuration into the data plane
   * @param clock time source, injected so tests are not dependent on the wall clock
   */
  public DeploymentService(
      DeploymentRepository deploymentRepository,
      ProcessedEventRepository processedEventRepository,
      ConfigWriter configWriter,
      Clock clock) {
    this.deploymentRepository = deploymentRepository;
    this.processedEventRepository = processedEventRepository;
    this.configWriter = configWriter;
    this.clock = clock;
  }

  /**
   * Records a deployment for an incoming rule event, unless that event was already handled.
   *
   * <p>The idempotency ledger row and the deployment row are written in one transaction, so a
   * failure part-way leaves neither.
   *
   * <p>The common replay is caught by the {@code existsById} check below, and the ledger's primary
   * key is the backstop for the race that check cannot win — two consumers in the same group both
   * seeing "absent" before either inserts. Only one of them can then succeed.
   *
   * <p><strong>The check is not redundant, and the order matters.</strong> Letting the constraint
   * fire and catching {@link DataIntegrityViolationException} here looks equivalent and is not: by
   * the time the exception is thrown, the transaction is already marked rollback-only, so catching
   * it does not save the transaction. The commit then fails, the listener sees an error, and the
   * record is redelivered — forever, because every redelivery fails the same way. The constraint is
   * still the guarantee; the check is what keeps the expected case off the exception path.
   *
   * @param eventId the envelope's event id
   * @param payload the rule's state after the change
   * @return true if this delivery did the work, false if it was a replay
   */
  @Transactional
  public boolean applyFromEvent(UUID eventId, RuleChangedPayload payload) {
    if (processedEventRepository.existsById(eventId)) {
      // At-least-once delivery makes this an expected outcome rather than an
      // error, so INFO rather than WARN, and the offset is still acknowledged.
      LOG.info("Event {} already processed, skipping", eventId);
      return false;
    }

    Deployment deployment =
        Deployment.pending(
            payload.ruleId(),
            payload.version(),
            payload.service(),
            payload.endpoint(),
            payload.limitValue(),
            payload.windowSpec(),
            clock.instant());

    processedEventRepository.save(ProcessedEvent.of(eventId, clock.instant()));
    apply(deployment);
    return true;
  }

  /**
   * Rolls a rule back to a previously deployed version.
   *
   * <p>Appends a new deployment rather than deleting or rewriting the newer ones, in the same
   * spirit as ADR 0007: the history then reads honestly as "v3, v4, then v3 again", which is what
   * actually happened.
   *
   * <p>The values come from the earlier deployment record rather than from the rule store, so a
   * rollback works without the control plane being reachable.
   *
   * @param ruleId the rule to roll back
   * @param toVersion the version to restore
   * @return the new deployment
   * @throws DeploymentOperationException if the rule has no history, the version was never
   *     successfully deployed, or it is already live
   */
  @Transactional
  public DeploymentResponse rollback(UUID ruleId, int toVersion) {
    Deployment latest =
        deploymentRepository
            .findLatest(ruleId)
            .orElseThrow(
                () ->
                    new DeploymentOperationException(
                        new DeploymentError.NoDeploymentsForRule(ruleId)));

    if (latest.getRuleVersion() == toVersion) {
      throw new DeploymentOperationException(
          new DeploymentError.VersionAlreadyLive(ruleId, toVersion));
    }

    Deployment previous =
        deploymentRepository
            .findLastSuccessfulAtVersion(ruleId, toVersion)
            .orElseThrow(
                () ->
                    new DeploymentOperationException(
                        new DeploymentError.VersionNeverDeployed(ruleId, toVersion)));

    Deployment restored =
        Deployment.pending(
            previous.getRuleId(),
            previous.getRuleVersion(),
            previous.getService(),
            previous.getEndpoint(),
            previous.getLimitValue(),
            previous.getWindowSpec(),
            clock.instant());

    apply(restored);
    return DeploymentResponse.of(restored);
  }

  /**
   * Retrieves one deployment.
   *
   * @param deploymentId the deployment to read
   * @return its current state
   * @throws DeploymentOperationException if no such deployment exists
   */
  @Transactional(readOnly = true)
  public DeploymentResponse get(UUID deploymentId) {
    return deploymentRepository
        .findById(deploymentId)
        .map(DeploymentResponse::of)
        .orElseThrow(
            () ->
                new DeploymentOperationException(
                    new DeploymentError.DeploymentNotFound(deploymentId)));
  }

  /**
   * Lists a rule's deployment history, newest first.
   *
   * @param ruleId the rule whose history to read
   * @param requestedSize desired page size; clamped to {@link #MAX_PAGE_SIZE}
   * @return that rule's deployments, newest first
   */
  @Transactional(readOnly = true)
  public List<DeploymentResponse> history(UUID ruleId, Integer requestedSize) {
    List<Deployment> deployments =
        deploymentRepository.findHistory(ruleId, Limit.of(clampPageSize(requestedSize)));
    List<DeploymentResponse> responses = new ArrayList<>(deployments.size());
    for (Deployment deployment : deployments) {
      responses.add(DeploymentResponse.of(deployment));
    }
    return responses;
  }

  /**
   * Writes the record, then installs the configuration.
   *
   * <p>The record is saved before the data plane is touched, so a crash mid-apply leaves evidence
   * that the attempt happened rather than losing it silently. A failure marks the row {@code
   * FAILED} rather than propagating: the attempt is a fact worth recording, and an exception
   * escaping a Kafka listener would roll back the idempotency ledger too and make the event
   * redeliver forever.
   */
  private void apply(Deployment deployment) {
    deploymentRepository.saveAndFlush(deployment);
    try {
      deployment.succeeded(configWriter.apply(deployment));
    } catch (ConfigWriteException e) {
      // Caught and not rethrown, deliberately. The attempt happened, and its
      // outcome belongs in the record - that is what makes a failure queryable
      // rather than only visible in a log. Rethrowing would also roll back the
      // idempotency ledger written above, so the event would redeliver forever
      // against a data plane that is going to reject it again.
      LOG.warn(
          "Could not apply deployment {} for rule {} v{}",
          deployment.getDeploymentId(),
          deployment.getRuleId(),
          deployment.getRuleVersion(),
          e);
      deployment.failed(e.getMessage());
    }
    deploymentRepository.saveAndFlush(deployment);
  }

  private static int clampPageSize(Integer requestedSize) {
    if (requestedSize == null) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.clamp(requestedSize, 1, MAX_PAGE_SIZE);
  }
}
