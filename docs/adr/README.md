# Architecture Decision Records

Each ADR records one decision: the context that forced it, what was decided, and
what it costs. They are immutable — a decision that changes gets a new ADR that
supersedes the old one, rather than an edit. The reasoning is the point, and
editing history destroys it.

Format: `NNNN-short-title.md`, numbered sequentially.

| ADR | Decision | Status |
| --- | -------- | ------ |
| [0001](0001-maven-over-gradle.md) | Maven as the build tool | Accepted |
| [0002](0002-java-21-target-on-jdk-26.md) | Target Java 21 while developing on JDK 26 | Accepted |
| [0003](0003-monorepo-multi-module.md) | Monorepo with a single parent build | Accepted |
| [0004](0004-postgresql-only.md) | PostgreSQL as the only datastore; DynamoDB deferred | Accepted |
| [0005](0005-kafka-event-backbone.md) | Kafka as the asynchronous backbone | Accepted |
| [0006](0006-trunk-based-development.md) | Trunk-based development | Accepted |
| [0007](0007-immutable-rule-versioning.md) | Rules are versioned, never mutated | Accepted |

## Open decisions

Recorded here until resolved, then written up as an ADR:

- **Rule version concurrency control** (Phase 1) — pessimistic
  `SELECT ... FOR UPDATE` versus optimistic `@Version` with retry. Decide while
  writing the migration and the concurrency test.
- **Trace backend** (Phase 4) — Jaeger or Tempo.
- **Kafka on AWS** (Phase 5) — MSK versus self-managed on EKS.
