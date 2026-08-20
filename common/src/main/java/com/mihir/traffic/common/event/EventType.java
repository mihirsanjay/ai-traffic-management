package com.mihir.traffic.common.event;

/**
 * The kinds of event this platform publishes.
 *
 * <p>An enum rather than a free string: a typo becomes a compile error rather than a message nobody
 * consumes. Adding a constant is an additive change and therefore allowed under the event-contract
 * rule in {@code CLAUDE.md}; removing or renaming one is not.
 *
 * <p><strong>Consumers should dispatch on the topic they received from, not on this field.</strong>
 * The topic is already the routing key, and treating {@code eventType} as an assertion rather than
 * a switch means a future producer emitting a constant an older consumer has never heard of cannot
 * break that consumer.
 */
public enum EventType {

  /** A rule was created at version 1. */
  RULE_CREATED,

  /** A rule gained a new immutable version. */
  RULE_UPDATED,

  /** A rule was soft-deleted. Its version history survives, per ADR 0007. */
  RULE_DELETED,

  /** A rule version was successfully applied to the data plane. */
  DEPLOYMENT_SUCCEEDED,

  /** A rule version could not be applied to the data plane. */
  DEPLOYMENT_FAILED
}
