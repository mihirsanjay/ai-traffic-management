package com.mihir.traffic.ruleservice.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a throttling rule.
 *
 * <p>Constraints are enforced at the edge rather than trusted from the client. Anything that gets
 * past them is a well-formed rule; anything that does not becomes a 400 with an RFC 7807 body.
 *
 * @param service the service to throttle, e.g. {@code orders}
 * @param endpoint the endpoint path to throttle, e.g. {@code /orders}
 * @param limit requests permitted per window
 * @param window the window the limit applies over, e.g. {@code 1m}
 */
public record CreateRuleRequest(
    @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9-]+$") String service,
    @NotBlank @Size(max = 200) @Pattern(regexp = "^/.*") String endpoint,
    @Min(1) @Max(1_000_000) int limit,
    // A duration such as 1s, 30s, 1m, or 1h. Kept deliberately narrow: an
    // unparseable window is a rule that cannot be enforced.
    @NotBlank @Pattern(regexp = "^[1-9][0-9]*[smh]$") String window) {}
