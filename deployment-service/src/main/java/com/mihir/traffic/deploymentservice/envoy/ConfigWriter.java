package com.mihir.traffic.deploymentservice.envoy;

import com.mihir.traffic.deploymentservice.domain.Deployment;

/**
 * Installs a deployment's configuration into the data plane.
 *
 * <p>An interface with a logging no-op implementation for now. Phase 3 replaces it with one that
 * renders Envoy route configuration and installs it by atomic move, then confirms the proxy
 * accepted it. Defining the seam now means that phase swaps an implementation rather than
 * restructuring the service around a new dependency.
 */
public interface ConfigWriter {

  /**
   * Applies a deployment to the data plane.
   *
   * @param deployment what to apply
   * @return the configuration version the data plane is now serving, or null if this writer does
   *     not produce one
   * @throws ConfigWriteException if the data plane rejected the configuration or did not confirm it
   */
  Long apply(Deployment deployment);
}
