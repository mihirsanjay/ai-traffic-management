-- Rule storage, implementing ADR 0007: rules are versioned, never mutated.
--
-- `rules` holds stable identity and a pointer to the current version.
-- `rule_versions` holds immutable history - one row per change, never updated.
-- A rule change is an INSERT into rule_versions plus a pointer move, never an
-- UPDATE of stored rule values. Rollback and audit both depend on prior
-- versions still existing.
--
-- Migrations are immutable once applied. Editing this file after it has run
-- breaks startup on a Flyway checksum mismatch; fix forward with a new
-- migration instead.

CREATE TABLE rules (
    rule_id         UUID         PRIMARY KEY,

    -- The API target this rule throttles. `service` is not reserved in
    -- PostgreSQL, but `limit` and `window` are, which is why the value columns
    -- in rule_versions carry suffixes.
    service         VARCHAR(100) NOT NULL,
    endpoint        VARCHAR(200) NOT NULL,

    -- Pointer to the live version, so "what version is this rule on" is
    -- answerable without scanning history. Per ADR 0007 the pointer is
    -- preferred over a join.
    current_version INTEGER      NOT NULL,

    -- Optimistic-locking counter (ADR 0008). Hibernate's @Version maps here:
    -- a concurrent update whose WHERE clause no longer matches this value
    -- affects zero rows and is retried, which is what stops two writers from
    -- both computing the same next version number.
    lock_version    BIGINT       NOT NULL DEFAULT 0,

    -- Soft delete. ADR 0007 requires deletion be a tombstone: hard deletion
    -- would destroy exactly the history this design exists to preserve.
    deleted_at      TIMESTAMPTZ,

    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(100) NOT NULL,

    CONSTRAINT rules_current_version_positive CHECK (current_version >= 1)
);

CREATE TABLE rule_versions (
    rule_id      UUID         NOT NULL,

    -- Version numbers are per rule and start at 1 - deliberately not drawn
    -- from a global sequence, which would produce confusing gaps (1, 7, 23)
    -- for no benefit.
    version      INTEGER      NOT NULL,

    -- `limit` and `window` are reserved words in SQL.
    limit_value  INTEGER      NOT NULL,
    window_spec  VARCHAR(20)  NOT NULL,

    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(100) NOT NULL,

    -- The composite primary key makes duplicate versions physically
    -- impossible. Correctness here does not depend on application code being
    -- right - the database rejects a second (rule_id, version) outright.
    CONSTRAINT rule_versions_pkey PRIMARY KEY (rule_id, version),

    CONSTRAINT rule_versions_rule_fk FOREIGN KEY (rule_id)
        REFERENCES rules (rule_id),

    CONSTRAINT rule_versions_version_positive CHECK (version >= 1),
    CONSTRAINT rule_versions_limit_positive   CHECK (limit_value >= 1)
);

-- Two *live* rules must not target the same endpoint, or which one applies
-- becomes ambiguous. Partial, so that soft-deleted rules do not block
-- recreating a rule for the same endpoint later.
CREATE UNIQUE INDEX rules_service_endpoint_live_idx
    ON rules (service, endpoint)
    WHERE deleted_at IS NULL;

-- Listing live rules is the common read path.
CREATE INDEX rules_live_created_at_idx
    ON rules (created_at, rule_id)
    WHERE deleted_at IS NULL;
