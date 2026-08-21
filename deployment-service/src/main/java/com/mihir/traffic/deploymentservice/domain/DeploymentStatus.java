package com.mihir.traffic.deploymentservice.domain;

/** Where a deployment attempt got to. */
public enum DeploymentStatus {

  /** Recorded, not yet applied to the data plane. */
  PENDING,

  /** Applied, and the data plane confirmed it. */
  SUCCEEDED,

  /** Could not be applied. {@code detail} says why. */
  FAILED
}
