package com.mihir.traffic.deploymentservice.web.dto;

import jakarta.validation.constraints.Min;

/**
 * A request to restore a previously deployed rule version.
 *
 * <p>The version is explicit rather than implied by "the previous one". Rollback during an incident
 * should say exactly what it is restoring, and a caller that has to name the version has to know
 * what it is asking for.
 *
 * @param toVersion the rule version to restore
 */
public record RollbackRequest(@Min(1) int toVersion) {}
