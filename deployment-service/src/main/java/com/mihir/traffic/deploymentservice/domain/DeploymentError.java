package com.mihir.traffic.deploymentservice.domain;

import java.util.UUID;

/**
 * Expected failures, as domain outcomes rather than exceptional events.
 *
 * <p>Sealed, so a switch over it is exhaustive and adding a variant produces a compile error at
 * every site that must handle it — which is exactly what should happen when a new failure mode
 * appears.
 */
public sealed interface DeploymentError {

  /** No deployment with that id. */
  record DeploymentNotFound(UUID deploymentId) implements DeploymentError {}

  /** The rule has never been deployed, so it has no history to roll back through. */
  record NoDeploymentsForRule(UUID ruleId) implements DeploymentError {}

  /** That version was never successfully deployed, so there is nothing to restore. */
  record VersionNeverDeployed(UUID ruleId, int ruleVersion) implements DeploymentError {}

  /** That version is already the live one. */
  record VersionAlreadyLive(UUID ruleId, int ruleVersion) implements DeploymentError {}
}
