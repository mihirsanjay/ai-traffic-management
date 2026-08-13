# 0004 — PostgreSQL as the only datastore; DynamoDB deferred

**Status:** Accepted · 2026-08-12

## Context

The original planning discussion listed DynamoDB as a candidate technology. The
platform needs durable storage for rules, rule version history, deployment
state, and audit records.

## Decision

PostgreSQL is the single source of truth. DynamoDB is **deferred**, not
rejected — revisit only if a genuine high-throughput key-value access pattern
appears that PostgreSQL cannot serve.

Adding a second datastore without an access pattern that demands it buys
operational cost and a second consistency model, in exchange for nothing. The
data here is relational: rules have versions, deployments reference rule
versions, audit entries reference both. Those are joins.

Redis is still used, but as a cache and for distributed rate-limit counters —
not as a source of truth. Losing Redis costs performance, never data.

## Consequences

- One backup, migration, and failover story rather than two.
- Rule version history gets transactional integrity for free: appending a
  version and moving the current-version pointer happen atomically.
- Foreign keys enforce referential integrity between rules, versions, and
  deployments at the database level.
- Flyway manages schema evolution.
- Horizontal write scaling is bounded by a single primary. Far beyond this
  project's needs; read replicas would come first if it mattered.
- The DynamoDB-specific skills the original plan gestured at are not exercised.
  Accepted deliberately — the plan's own principle is that each technology must
  solve a problem the project actually has.
