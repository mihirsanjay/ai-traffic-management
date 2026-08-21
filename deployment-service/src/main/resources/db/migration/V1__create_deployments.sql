-- Deployment records and the idempotency ledger that guards them.
--
-- This service owns its own database. Neither Kafka nor Envoy can answer
-- "which rule version is live right now, and did applying it succeed" - Kafka
-- retention expires, and Envoy holds current configuration with no history.
-- Rollback additionally needs to know what was live *before*, which only a
-- durable record provides.

CREATE TABLE deployments (
    deployment_id  UUID         PRIMARY KEY,

    -- The target. Immutable once written: a deployment refers to one specific
    -- rule version (ADR 0007), which is what makes it meaningful to say "roll
    -- back to version 3" months later.
    rule_id        UUID         NOT NULL,
    rule_version   INTEGER      NOT NULL,

    -- The rule's targeting and values, denormalised from the event payload.
    --
    -- Deliberate, and the reason is a service boundary rather than performance:
    -- this service cannot read rule-service's tables (architecture.md), so the
    -- event is the only source for these. Copying them here means the complete
    -- desired state of the data plane can be assembled from this table alone,
    -- with no call back to the control plane - which is what keeps a
    -- rule-service outage from blocking a deployment.
    service        VARCHAR(100) NOT NULL,
    endpoint       VARCHAR(200) NOT NULL,

    -- NULL for a deletion: the rule is gone and has no limit. The targeting
    -- above is still needed, to know which route to stop enforcing.
    limit_value    INTEGER,
    window_spec    VARCHAR(20),

    status         VARCHAR(20)  NOT NULL,

    -- Why a failure failed. Null on success.
    detail         TEXT,

    -- The configuration version this deployment produced, once Phase 3 renders
    -- real Envoy config. Null until then, and null for a deployment that never
    -- reached the data plane.
    config_version BIGINT,

    created_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT deployments_status_valid
      CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT deployments_version_positive CHECK (rule_version >= 1)
);

-- Both real queries are "this rule's deployments, newest first": what is live
-- now, and what preceded it.
CREATE INDEX deployments_rule_idx ON deployments (rule_id, created_at DESC);

-- Idempotency. Kafka delivers at-least-once, so the same event WILL arrive
-- twice - on a rebalance, on a redelivery after a failed commit, or when the
-- publisher restarts between sending and marking a row published.
--
-- The consumer checks this table before inserting, and inserts into it in the
-- same transaction as the deployment row.
--
-- Both halves are needed, which is not obvious. The check handles the ordinary
-- replay. The primary key handles the race the check cannot win - two consumers
-- in the same group both reading "absent" before either writes - and only one of
-- them can then commit.
--
-- Relying on the constraint ALONE does not work, and fails in a way that looks
-- like it should: once Postgres raises 23505 the transaction is already marked
-- rollback-only, so catching the exception does not rescue the commit. The
-- listener sees a failure, the record is redelivered, and every redelivery
-- fails identically - an infinite loop rather than a clean skip.
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE deployments IS
  'One row per attempt to apply a rule version to the data plane.';
COMMENT ON TABLE processed_events IS
  'Event ids already handled. Makes at-least-once delivery safe to replay.';
