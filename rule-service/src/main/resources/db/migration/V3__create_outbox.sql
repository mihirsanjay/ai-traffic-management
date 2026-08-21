-- Transactional outbox: events wait here until they reach Kafka.
--
-- The problem this solves is that a database commit and a Kafka publish cannot
-- be made atomic. Publishing inside the transaction risks announcing a change
-- that then fails to commit - an event describing something that never
-- happened. Publishing after the commit risks the process dying in between, and
-- the event is lost with no record that it was owed.
--
-- Writing the event to this table in the SAME transaction as the state change
-- removes the choice: either both are durable or neither is. A separate poller
-- then moves rows to Kafka, and because it can crash and retry, delivery is
-- at-least-once - which is what consumers already have to tolerate (ADR 0005).

CREATE TABLE outbox (
    -- The event's own identity, generated when this row is written and never
    -- regenerated. Consumers deduplicate on it, so it must survive republishing
    -- unchanged; a fresh id per publish attempt would defeat their idempotency
    -- entirely. It is the primary key rather than a surrogate because the event
    -- id IS the identity - there is nothing else to be unique on.
    event_id      UUID         PRIMARY KEY,

    -- The Kafka message key, and therefore the partition. Partitioning by rule
    -- id is what gives per-rule ordering: Kafka orders within a partition and
    -- nowhere else. Named for the aggregate rather than the rule so deployment
    -- events can reuse this shape without a migration.
    aggregate_id  UUID         NOT NULL,

    -- Resolved at write time, not publish time, so the publisher never switches
    -- on event type. A new event type then ships without touching it.
    topic         VARCHAR(100) NOT NULL,
    event_type    VARCHAR(50)  NOT NULL,

    -- The serialized envelope, ready to send as-is. JSONB rather than TEXT: it
    -- costs nothing extra, it rejects anything that is not valid JSON at write
    -- time rather than at publish time, and it makes "what is stuck in the
    -- outbox" answerable in SQL instead of by squinting at blobs.
    payload       JSONB        NOT NULL,

    -- Captured here rather than read by the publisher, which runs on a
    -- scheduler thread with no relationship to the request that caused the
    -- event. Nullable: not every write has a correlation id.
    trace_id      VARCHAR(128),

    occurred_at   TIMESTAMPTZ  NOT NULL,

    -- NULL means unpublished. A nullable timestamp rather than a status enum or
    -- a boolean, because it answers both "has it gone" and "when did it go",
    -- and because it is what makes the partial index below possible.
    --
    -- There is deliberately no attempt counter and no last_error column. The
    -- pattern's load-bearing property is that this row commits with the state
    -- change; per-row failure bookkeeping is operational polish, and the failure
    -- mode here is "Kafka is unreachable", which is all-or-nothing across every
    -- row rather than something a per-row counter would illuminate.
    published_at  TIMESTAMPTZ
);

-- The publisher's only query. Partial on published_at IS NULL, which is what
-- keeps it small forever: it indexes the backlog, not the history. A published
-- row leaves the index entirely, so its size tracks queue depth rather than
-- total events ever emitted, and the poll stays fast as the table grows.
CREATE INDEX outbox_unpublished_idx
    ON outbox (occurred_at, event_id)
    WHERE published_at IS NULL;

COMMENT ON TABLE outbox IS
  'Events awaiting publication to Kafka. Written in the same transaction as the '
  'state change they describe; drained by OutboxPublisher.';
