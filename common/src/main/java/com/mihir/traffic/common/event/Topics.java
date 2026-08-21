package com.mihir.traffic.common.event;

/**
 * Kafka topic names, following the {@code <domain>.<entity>.<event-type>} convention from {@code
 * docs/architecture.md}.
 *
 * <p>Genuinely shared: a producer and a consumer must agree on the exact string, which makes this
 * the integration surface ADR 0003 permits {@code common} to hold. Auto-creation is disabled on the
 * broker, so a name that does not match {@code infra/kafka/create-topics.sh} fails loudly at
 * publish time rather than silently creating a topic nobody reads.
 */
public final class Topics {

  /** A rule was created. Partitioned by rule ID. */
  public static final String RULE_CREATED = "control.rule.created";

  /** A rule gained a new version. Partitioned by rule ID. */
  public static final String RULE_UPDATED = "control.rule.updated";

  /** A rule was soft-deleted. Partitioned by rule ID. */
  public static final String RULE_DELETED = "control.rule.deleted";

  /**
   * A deployment succeeded.
   *
   * <p>Published with no consumer, deliberately. It is the standing demonstration that a producer
   * neither knows nor cares who is listening — see ADR 0009.
   */
  public static final String DEPLOYMENT_SUCCEEDED = "control.deployment.succeeded";

  /** A deployment failed. Published with no consumer, for the same reason as its sibling. */
  public static final String DEPLOYMENT_FAILED = "control.deployment.failed";

  /**
   * Returns the topic a rule event of the given type belongs on.
   *
   * @param eventType the kind of rule event
   * @return the topic name
   * @throws IllegalArgumentException if the type is not a rule event
   */
  public static String forRuleEvent(EventType eventType) {
    return switch (eventType) {
      case RULE_CREATED -> RULE_CREATED;
      case RULE_UPDATED -> RULE_UPDATED;
      case RULE_DELETED -> RULE_DELETED;
      case DEPLOYMENT_SUCCEEDED, DEPLOYMENT_FAILED ->
          throw new IllegalArgumentException(eventType + " is not a rule event");
    };
  }

  private Topics() {
    throw new AssertionError("Topics is a constant holder and must not be instantiated");
  }
}
