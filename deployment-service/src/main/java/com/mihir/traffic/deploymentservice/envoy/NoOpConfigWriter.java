package com.mihir.traffic.deploymentservice.envoy;

import com.mihir.traffic.deploymentservice.domain.Deployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records what would be applied, and applies nothing.
 *
 * <p>Phase 2 builds the event path: a rule change reaches this service, becomes a durable
 * deployment record, and is queryable. There is no data plane yet to apply it to — Envoy arrives in
 * Phase 3, along with the writer that replaces this one.
 *
 * <p>It logs rather than doing nothing silently, so an end-to-end run through the event path is
 * observable before there is a proxy to observe.
 */
@Component
public class NoOpConfigWriter implements ConfigWriter {

  private static final Logger LOG = LoggerFactory.getLogger(NoOpConfigWriter.class);

  @Override
  public Long apply(Deployment deployment) {
    if (deployment.isDeletion()) {
      LOG.info(
          "Would remove throttling for {} {} (rule {} v{})",
          deployment.getService(),
          deployment.getEndpoint(),
          deployment.getRuleId(),
          deployment.getRuleVersion());
    } else {
      LOG.info(
          "Would apply {} per {} to {} {} (rule {} v{})",
          deployment.getLimitValue(),
          deployment.getWindowSpec(),
          deployment.getService(),
          deployment.getEndpoint(),
          deployment.getRuleId(),
          deployment.getRuleVersion());
    }
    // No configuration version until something real is written.
    return null;
  }
}
