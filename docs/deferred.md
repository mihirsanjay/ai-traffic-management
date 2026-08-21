# Deferred

Scope cut from the roadmap on **2026-08-20** in favour of breadth: finishing the
core platform in three sessions and then deploying it to a real cloud with CI/CD
and secrets management.

Nothing here is rejected on merit. Each entry names what it would have taught and
what the cheapest path back in would be, so that picking it up later is a decision
with known cost rather than an archaeology exercise.

## Deferred, with intent to revisit

| Item | What it teaches | Cost to add later | Trigger to revisit |
| --- | --- | --- | --- |
| **Distributed tracing** (OpenTelemetry + Jaeger or Tempo) | Trace continuity across a queue boundary; the API → Kafka → Deployment → Envoy path as a single trace | **Low.** Auto-instrumentation plus trace-ID propagation through Kafka headers. No schema change. Phase 4 ships a correlation ID through the same seam, so the wiring point already exists. | After Phase 5, when there are enough hops that reading three logs stops being enough |
| **Prometheus + Grafana** | Dashboards committed as code rather than hand-clicked; cardinality discipline | **Low.** Micrometer already emits on `/actuator/prometheus`; only the scrape config and dashboard JSON are missing | Phase 6, when a cloud deployment needs observability you cannot get by tailing a local container |
| **Failure-injection catalogue** (`docs/failure-scenarios.md`, `infra/chaos/`) | Falsifying each distributed-systems property the platform claims | **Medium.** Each scenario is a script, a documented expectation, and a run | Any time a claimed property is doubted. The highest-value three: kill deployment-service mid-apply, deliver a config version out of order, take Kafka down and restore |
| **Circuit breakers** (Resilience4j) | Failing fast versus queueing work that cannot complete | **Low** | When a second remote dependency appears on a hot path. Today the only one is Envoy's admin API, already bounded by an explicit timeout |
| **Redis caching on the enforcement read path** | Cache invalidation against a versioned source of truth | **Low** | Only if a read path becomes hot. With enforcement in Envoy there is currently no application read path at all |
| **HPA, blue/green, rolling deploys** | Horizontal scaling and zero-downtime rollout | **Medium** | Phase 5, if local Kubernetes goes faster than budgeted |
| **Third simulator** (`inventory-service`) | Nothing new — it is a copy of the other two | **Trivial** | If a demo needs a third upstream |

## Dropped, not deferred

| Item | Why |
| --- | --- |
| **`audit-service`** | Its architectural purpose was demonstrating a second independent consumer. The underlying data still exists in `rule_versions` and is exposed by `GET /rules/{id}/versions`; what is missing is a queryable cross-rule log. See [ADR 0009](adr/0009-drop-audit-and-analytics-services.md) |
| **`analytics-service`** | Aggregation over a traffic stream is a data-engineering exercise orthogonal to the control-plane concepts this project practises. Envoy's `local_ratelimit` stats give throttle visibility for free. See [ADR 0009](adr/0009-drop-audit-and-analytics-services.md) |
| **`REQUEST_ALLOWED` / `REQUEST_THROTTLED` events** | Existed only to feed Analytics. Envoy has no native Kafka sink, so producing them would have required a log-shipping sidecar — new scope for a consumer that no longer exists |

## Testing scope deliberately not written

Recorded so their absence reads as a decision rather than an oversight — see
[ADR 0011](adr/0011-thinned-test-strategy.md).

`learning-map.md` makes *"a concurrency test that passes proves nothing until you
prove it can fail"* a headline lesson of Phase 1. That belief has not changed.
What changed is the budget, and these are what it bought:

- **Vacuity checks** — deliberately breaking a test to confirm it can fail. Phase 1
  did this twice and both times it caught a test asserting nothing.
- **Concurrency stress tests** beyond the one already in Phase 1.
- **Fail-open probes** beyond the one already in Phase 1.
- **More than one happy-path integration test per capability.**
- **DLT-landing tests** — the error handler is retained, but no test asserts a
  poison message reaches the dead-letter topic.

Two tests remain non-negotiable and are not on this list: the Kafka consumer
duplicate-delivery test, and the proof that no Kafka publish happens inside a
database transaction.

## Reduced rather than dropped

| Item | Full form | What ships instead |
| --- | --- | --- |
| **`Idempotency-Key` on deployments** | Store the response body per key and replay it on retry | A unique constraint: the same key plus the same rule version returns the existing deployment rather than creating a second one. Satisfies the intent at roughly a tenth of the cost |
| **Retry and dead-lettering** | Exponential backoff with jitter, DLT replay endpoints, DLT monitoring | `DefaultErrorHandler` with a fixed backoff and a `DeadLetterPublishingRecoverer` — about eight lines, and enough that a poison message does not block its partition |
| **Outbox bookkeeping** | `attempts`, `last_error`, a status enum | A single nullable `published_at`. The pattern's load-bearing property is that the event row commits in the same transaction as the state change; the rest is operational polish |
| **Observability** | OpenTelemetry, Jaeger, Prometheus, Grafana, structured logs, real readiness | Structured JSON logs, a correlation ID that survives the Kafka boundary, five Micrometer metrics, and readiness probes that reflect real dependency health |
