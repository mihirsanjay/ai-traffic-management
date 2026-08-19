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
| 1 | Core Java, Spring Boot, REST, PostgreSQL, JUnit, Testcontainers | 1 | Complete |
| 2 | Rate limiting: token buckets, windows, burst, 429 | 1 | Complete |
| 3 | Deployment and configuration management, rollback, idempotency | 2 | Pending |
| 4 | Event-driven architecture with Kafka | 2 | Pending |
| 5 | Distributed data plane (Envoy), dynamic config / xDS | 3 | Pending |
| 6 | Simulated production environment and traffic generation | 3 | Pending |
| 7 | Redis for distributed state and caching | 1 | Complete |
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

## Phase 1 — Control plane core · **Complete** (2026-08-19)

**Concepts to practise:** REST API design and versioning · Bean Validation at
the edge · RFC 7807 error responses · JPA and Flyway migrations · immutable
versioning as an append-only log · **optimistic concurrency control** and the
transaction/retry boundary · keyset (cursor) pagination · token-bucket rate
limiting with distributed Redis counters · Testcontainers integration testing.

**The question this phase answers:** two people edit the same rule at the same
millisecond — how does the system avoid giving them the same version number, and
what does the loser see?

**What it taught.**

The answer to the phase's question turned out to be two answers, because the
race has two shapes. A losing writer either fails the conditional update on
`rules.lock_version` and surfaces `ConcurrencyFailureException`, or gets there
first with its `INSERT` and hits the `(rule_id, version)` primary key as
`DataIntegrityViolationException`. Both mean "someone beat you to version N",
and `VersionConflictRetrier` has to catch both. Designing against the mechanism
described in ADR 0008 — the `@Version` column — would have handled only half the
collisions. The database's own constraint is the thing that actually guarantees
correctness; the locking strategy only decides whether the loser gets a clean
retry or an ugly 500.

**A retry must live outside the transaction it retries.** Retrying inside the
boundary re-runs work against a rolled-back state and fails identically every
time, forever. That forced the structure: the retry loop sits outside, and each
attempt calls back into a fresh `@Transactional` method. The related trap cost
real time — Spring applies `@Transactional` through a proxy, so calling the
annotated method on `this` makes the annotation *silently inert*. The version
insert and the pointer move then commit as two independent statements, and a
crash between them orphans the pointer. Nothing fails loudly; the code looks
right. It was caught only by explicitly probing for an active transaction, and
`RuleVersionAppenderIntegrationTest` now fails if the structure is undone. The
general lesson: framework magic that works by proxy fails by silence.

**A concurrency test that passes proves nothing until you prove it can fail.**
Eight latch-released writers against one rule passed on the first run — which is
equally consistent with a working retry and with the writers never actually
colliding. Setting `max-attempts: 1` showed seven of the eight genuinely losing
the race. Only then did the passing version mean something. This is the same
lesson Phase 0 learned from `git push --dry-run`: a check that cannot fail is
not evidence.

**Test isolation failures pass locally and fail everywhere else.** Spring only
auto-detects a nested `@TestConfiguration` on the test class being run, not one
inherited from an abstract base. The Testcontainers Postgres started, the
`@ServiceConnection` never reached the datasource, and the app quietly fell back
to the URL in `application.yml` — so the suite passed whenever
`infra/docker-compose.yml` happened to be up. The container has to be `@Import`ed
explicitly. Its sibling cost more: `SpringApplicationBuilder.properties()` ranks
*below* a committed `application.yml`, so the second instance in the distributed
rate-limit test silently ignored the container's Redis port and connected to
whatever Redis was running locally. It shared no bucket with the first instance
— which made a genuinely broken limiter look like a passing distributed one.
Settings that `application.yml` also defines have to be passed as command-line
arguments via `run("--key=value")`, which outrank the file. Both bugs share a
shape: the test appears to configure something, the configuration silently does
not take, and the result is a green test asserting nothing. Verify by breaking
the dependency you think you are using — stop the local container and see
whether the test still passes.

**Dependency convergence proves one version loads, not that its methods exist.**
Bucket4j 8.14.0 declares lettuce-core 6.1.8 while Spring Boot 4 manages 7.5.2.
Excluding Bucket4j's copy satisfies the Enforcer, but a method removed between
majors would still surface as a `NoSuchMethodError` at runtime — the exact
failure the rule warns about and cannot itself detect.
`Bucket4jLettuceCompatibilityTest` exists to exercise that seam directly.

**Fail-open was a decision, not a default.** The limiter guards the control
plane's API rather than being a precondition of it, so a Redis outage costs the
safeguard, not the service. Proving it required really stopping Redis: a mocked
failure shows the catch block runs, while only killing the dependency shows the
timeout actually fires and the request still completes. An unbounded wait would
have converted a degraded cache into a total outage.

Two smaller things worth keeping. Cursor pagination needs `(createdAt, ruleId)`,
not `createdAt` alone — ordering by a non-unique key is precisely how keyset
pagination skips and repeats rows. And a retry exhausting its budget is a `409`,
not a masked success: the honest answer to "I could not do this" is an error,
and `WARN` rather than `ERROR`, because a human only needs to act if it becomes
frequent.

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
