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
| [0008](0008-optimistic-locking-for-rule-versions.md) | Optimistic locking for rule version increment | Accepted |
| [0009](0009-drop-audit-and-analytics-services.md) | Drop the audit and analytics services | Accepted |
| 0010 | Envoy `local_ratelimit` with filesystem xDS | *Pending — written in Phase 3* |
| [0011](0011-thinned-test-strategy.md) | Thinned test strategy and a 0.60 coverage floor | Accepted |
| 0012 | Cloud target and secrets management | *Pending — written in Phase 6* |

## Open decisions

Recorded here until resolved, then written up as an ADR:

- **Cloud provider** (Phase 6) — managed Kubernetes on GCP, AWS, or Azure. The
  deciding factors are control-plane cost, which differs by roughly $70/month,
  and which provider's IAM model is worth learning. Becomes ADR 0012.
- **Kafka in the cloud** (Phase 6) — self-hosted in-cluster versus a managed free
  tier. Managed Kafka is the most expensive line item by a wide margin for a
  broker at near-zero throughput.

Deferred rather than open — see [deferred.md](../deferred.md) — is the choice of
trace backend, which is not a decision until tracing is actually being added.
