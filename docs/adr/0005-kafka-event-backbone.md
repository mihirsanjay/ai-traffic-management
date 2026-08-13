# 0005 — Kafka as the asynchronous backbone

**Status:** Accepted · 2026-08-12

## Context

When a rule changes, several things must happen: it must be deployed to the
traffic layer, recorded in the audit log, and reflected in analytics. The Rule
Management Service could call each of those services directly over REST.

## Decision

Rule Management publishes events to Kafka. Consumers react independently.

Synchronous fan-out couples the producer to every consumer: Rule Management
would need to know each downstream service exists, its request contract, and
what to do when one is down. Adding a consumer would mean changing the producer.
An audit-service outage would fail rule creation — an obviously wrong coupling.

With Kafka, Rule Management publishes and is done. Consumers are added without
touching it, and a consumer being down delays that consumer's work rather than
failing the write.

## Consequences

- **Delivery is at-least-once, so every consumer must be idempotent.** This is
  the central correctness requirement of the whole event layer, not a detail:
  consumers key on `eventId` and a duplicate must be provably a no-op.
- **Publishing cannot happen inside a database transaction.** The commit can
  fail after the publish succeeds, leaving an event describing a state change
  that never happened. The transactional outbox pattern is mandatory: write the
  event to an outbox table in the same transaction, publish from there.
- The write path becomes eventually consistent. A rule exists immediately;
  its deployment lands shortly after. Deployment status is therefore a separate
  query, not part of the create response.
- Ordering holds per partition only. Partitioning by rule ID gives per-rule
  ordering; cross-rule ordering is not guaranteed and must not be relied on.
- Kafka is operational surface: a broker to run locally, MSK or self-managed on
  AWS.
- Debugging spans a queue boundary, which is precisely why trace-ID propagation
  through event headers is a Phase 4 requirement.
