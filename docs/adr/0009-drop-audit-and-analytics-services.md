# 0009 — Drop the audit and analytics services

**Status:** Accepted · 2026-08-20

## Context

The roadmap specified four services consuming the platform's event stream:
Deployment, Audit, and Analytics as Kafka consumers, plus three simulated
business services as traffic targets. Phases 2 and 3 would have built them.

Two things changed. The project's goal was restated as **breadth over depth** —
a system deployed end-to-end to a real cloud, with CI/CD and secrets management —
and the remaining budget was fixed at three coding sessions before that cloud
work begins. The original scope does not fit, so something is cut deliberately
rather than half-built.

Audit and Analytics are the natural candidates. Neither is on the path from
"a service owner writes a rule" to "traffic is throttled." Audit records who
changed what; Analytics aggregates traffic statistics. Both are useful products
and neither teaches a concept the rest of the platform does not already exercise:
Audit is a second Kafka consumer writing rows, and Analytics is aggregation over
a stream, which is a data-engineering exercise orthogonal to control-plane design.

## Decision

**`audit-service` and `analytics-service` are not built.** The Deployment Service
becomes the only consumer of the rule event stream.

The `REQUEST_ALLOWED` and `REQUEST_THROTTLED` events are removed from the event
catalogue entirely — they existed only to feed Analytics, and Envoy has no native
Kafka sink to produce them without a log-shipping sidecar that would itself have
been new scope.

## The challenge this creates for ADR 0005

ADR 0005 chose Kafka over synchronous REST fan-out, and two of its stated reasons
were specifically about having several consumers:

> *"An audit-service outage would fail rule creation — an obviously wrong coupling."*

> *"Consumers are added without touching it."*

With one consumer, neither statement demonstrates itself. A reader arriving at
this repository could fairly ask why `rule-service` publishes to Kafka at all
rather than calling `deployment-service` over HTTP.

**The answer is that the capability is retained; only the demonstration is
dropped.** Concretely, all of the following still hold and none of them required
Audit or Analytics to exist:

- Events are published to topics, not to a recipient. `rule-service` does not
  know `deployment-service` exists, and nothing in it would change if a second
  consumer were added tomorrow.
- Partitioning by rule ID preserves per-rule ordering for any number of
  consumers, not just this one.
- Consumer groups mean a new consumer reads the full stream from its own offset
  without coordinating with the existing one.
- `DEPLOYMENT_SUCCEEDED` and `DEPLOYMENT_FAILED` are still published, and
  currently have **no consumer at all**. They are the standing proof that the
  producer does not care whether anyone is listening.
- A consumer being down delays that consumer's work rather than failing the
  write — still true, and still the property that a synchronous call would lose.

The decoupling and the durability are real and load-bearing. What is gone is the
*visible* fan-out that made them obvious at a glance.

This ADR therefore **constrains the scope of ADR 0005 rather than superseding
it**. ADR 0005 remains Accepted, and its reasoning stands; two of its supporting
examples are simply no longer instantiated in this codebase.

## Consequences

- Kafka has exactly one consumer. Adding a second is the cheapest way to make the
  fan-out property demonstrable again, and it requires no change to any producer.
- `architecture.md`'s event table drops from seven events to five.
- The `traffic.request.allowed` and `traffic.request.throttled` topics are
  removed from `infra/kafka/create-topics.sh`, and the `audit_service` and
  `analytics_service` databases from the Postgres init script. Both are dead
  infrastructure that would otherwise mislead.
- Throttle telemetry does not disappear — Envoy's `local_ratelimit` filter
  exposes `ok`, `rate_limited`, and `enforced` counters on its admin `/stats`
  endpoint for free. What is lost is historical aggregation and the
  traffic-insight queries, not visibility into whether throttling works.
- The roadmap exit criterion *"a rule change produces an event that both
  consumers process independently"* is removed, since there is one consumer.
- **Audit history is genuinely lost as a product feature.** ADR 0007 justified
  immutable rule versioning partly on being able to answer *"User X changed the
  Orders quota from 100 to 500."* The data to answer it still exists in
  `rule_versions` — every version retains its `created_by` and `created_at` — and
  `GET /rules/{id}/versions` exposes it. What is missing is a service that
  aggregates that into a queryable cross-rule log. ADR 0007 is unaffected;
  versioning is still required for rollback and for knowing what is live.
- Neither service is rejected on merit. Both are recorded in `docs/deferred.md`
  with what they would have taught and the cost of adding them later, which for
  Audit is genuinely small — one consumer, one table, one endpoint.
