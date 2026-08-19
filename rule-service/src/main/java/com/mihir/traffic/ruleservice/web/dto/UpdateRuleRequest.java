package com.mihir.traffic.ruleservice.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for updating a throttling rule's values.
 *
 * <p>Deliberately narrower than {@link CreateRuleRequest}: {@code service} and {@code endpoint} are
 * the rule's identity, not its values. Changing what a rule targets would make its version history
 * describe two different things, so that is a delete plus a create rather than an update.
 *
 * <p>Constraints mirror {@link CreateRuleRequest} exactly - a value that could not have been
 * created must not be reachable by updating.
 *
 * @param limit requests permitted per window
 * @param window the window the limit applies over, e.g. {@code 1m}
 */
public record UpdateRuleRequest(
    @Min(1) @Max(1_000_000) int limit,
    @NotBlank @Pattern(regexp = "^[1-9][0-9]*[smh]$") String window) {}
