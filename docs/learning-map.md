# Learning Map

What each phase actually teaches, and where it stands.

`roadmap.md` is organised by **architecture** — which module gets built when.
This file is the same project organised by **concept**: what theory is being
practised, and where it is implemented. Two views of one plan.

The **Status** column is maintained automatically by the `Stop` hook
(`.claude/hooks/learning-map.sh`) when every exit criterion for a phase is
ticked in `roadmap.md`. The prose in **What it taught** is written by hand at
the end of each phase — that part is the point of the file and cannot be
generated.

## Topic coverage

| # | Learning topic | Phase | Status |
| --- | --- | --- | --- |
| 1 | Core Java, Spring Boot, REST, PostgreSQL, JUnit, Testcontainers | 1 | Pending |
| 2 | Rate limiting: token buckets, windows, burst, 429 | 1 | Pending |
| 3 | Deployment and configuration management, rollback, idempotency | 2 | Pending |
| 4 | Event-driven architecture with Kafka | 2 | Pending |
| 5 | Distributed data plane (Envoy), dynamic config / xDS | 3 | Pending |
| 6 | Simulated production environment and traffic generation | 3 | Pending |
| 7 | Redis for distributed state and caching | 1 | Pending |
| 8 | Microservices boundaries and ownership | 2 | Pending |
| 9 | Observability: metrics, tracing, structured logging | 4 | Pending |
| 10 | Distributed-systems hardening and failure injection | 4 | Pending |
| 11 | Docker and Kubernetes | 5 | Pending |
| 12 | CI/CD and safe deployment strategies | 0, 5 | Pending |
| 13 | Analytics and traffic insights | 3 | Pending |
| 14 | AI agents, tool calling, evaluation harnesses | 6 | Pending |
| 15 | AWS: EKS, IAM, Terraform | 5b | Deferred |

Topics 7 and 12 span phases: Redis arrives in Phase 1 for rate-limit counters
and returns in Phase 3 for hot-path caching; CI lands in Phase 0 and deployment
pipelines in Phase 5.

---

## Phase 0 — Foundation · **Complete** (2026-08-13)

**Concepts practised:** Maven multi-module builds · dependency and plugin
management · automated quality gates · containerised local infrastructure ·
Kafka in KRaft mode · trunk-based development with protected branches.

**What it taught.**

Quality gates only work if they fail the build. Every gate here — Spotless,
Checkstyle, SpotBugs, JaCoCo, Enforcer — breaks the build rather than warning,
and that was verified by deliberately injecting a formatting violation and
watching it fail. A warning is a suggestion nobody reads.

Kafka needs *two* listeners locally, and the reason generalises: a broker
advertises the address a client should use **after** the initial metadata
exchange, so host clients and in-network containers need different addresses. A
single listener necessarily breaks one of them. This is the same
advertised-address problem that appears in every service-discovery system.

Verifying infrastructure means exercising it, not reading container status. A
container reporting `healthy` proves a process is running, not that the thing
works: the real checks were a Postgres write/read round-trip, a Redis SET/GET,
and a Kafka produce/consume through the *host* listener specifically.

`git push --dry-run` does not contact the remote's protection rules — it passed
against a branch that then genuinely rejected the push with `GH006`. A check
that cannot fail proves nothing.

---

## Phase 1 — Control plane core · Pending

**Concepts to practise:** REST API design and versioning · Bean Validation at
the edge · RFC 7807 error responses · JPA and Flyway migrations · immutable
versioning as an append-only log · **optimistic concurrency control** and the
transaction/retry boundary · keyset (cursor) pagination · token-bucket rate
limiting with distributed Redis counters · Testcontainers integration testing.

**The question this phase answers:** two people edit the same rule at the same
millisecond — how does the system avoid giving them the same version number, and
what does the loser see?

*(Summary written when the phase completes.)*

---

## Phase 2 — Event backbone · Pending

**Concepts to practise:** the transactional outbox pattern · at-least-once
delivery and why it forces idempotent consumers · event schemas as a public API
· consumer groups, partitions, and per-entity ordering · retry with exponential
backoff and jitter · dead-letter topics · independent consumers reacting to one
event stream.

**The question this phase answers:** the database commit succeeds but the Kafka
publish fails — where did the event go, and how does the system not lie about
what happened?

*(Summary written when the phase completes.)*

---

## Phase 3 — Real data plane · Pending

**Concepts to practise:** control plane versus data plane · Envoy configuration
and dynamic config push (xDS) · moving enforcement out of the application and
into infrastructure · realistic load generation · traffic aggregation and
insight queries.

**The question this phase answers:** how does a control plane reconfigure a
fleet of proxies without dropping in-flight requests?

*(Summary written when the phase completes.)*

---

## Phase 4 — Observability and hardening · Pending

**Concepts to practise:** metrics, cardinality discipline, distributed tracing
across a queue boundary · structured logging correlated by trace ID · readiness
that reflects real dependency health · **deliberate failure injection**:
duplicate events, out-of-order configuration, dependency outages, mid-deployment
kills · fail-open versus fail-closed as an explicit decision.

**The question this phase answers:** when the platform misbehaves at 3am, can
you tell what happened from logs, metrics, and traces alone?

*(Summary written when the phase completes.)*

---

## Phase 5 — Production infrastructure · Pending

**Concepts to practise:** container image hygiene · Kubernetes deployments,
probes, ConfigMaps, Secrets · horizontal autoscaling under load · rolling and
blue/green deployment.

*(Summary written when the phase completes.)*

---

## Phase 6 — AI layer · Pending

**Concepts to practise:** tool calling and MCP · agents constrained to defined
APIs rather than direct data access · human-in-the-loop approval · evaluation
harnesses and regression scoring for non-deterministic systems.

*(Summary written when the phase completes.)*

---

## Phase 5b — AWS · Deferred

Optional, and deliberately last. See `roadmap.md`.
