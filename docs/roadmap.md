# Roadmap

Phases are **sequential**. Each builds on a working previous phase.

Every phase below has three parts:

- **What gets built** — the concrete modules and files that must exist. A module
  is introduced by exactly one phase; nothing appears without being built first.
- **Done when** — checkable exit criteria. Do not start the next phase until
  every box is ticked.
- **Git** — the branches, PRs, and release tag for the phase.

The single most important sequencing rule: **the AI layer is last.** Agents
operating a platform that does not yet work have nothing to operate, and their
failures become impossible to distinguish from the platform's.

## Module introduction map

Which phase first creates each module — the answer to "when do we actually build
the Deployment Service?"

| Module                            | Introduced in |
| --------------------------------- | ------------- |
| `pom.xml` (parent), `common/`      | Phase 0       |
| `infra/`                           | Phase 0       |
| `rule-service/`                    | Phase 1       |
| `deployment-service/`              | Phase 2       |
| `audit-service/`                   | Phase 2       |
| `simulators/{orders,payments,inventory}-service/` | Phase 3 |
| `analytics-service/`               | Phase 3       |
| `infra/envoy/`                     | Phase 3       |
| `infra/chaos/`                     | Phase 4       |
| `infra/k8s/`                       | Phase 5       |
| `agents/`                          | Phase 6       |
| `infra/terraform/`                 | Phase 5b *(deferred)* |

---

## Phase 0 — Foundation

Turn an empty repo into one that builds, tests, and enforces standards.

### What gets built

- `pom.xml` — parent POM: Java 21 target (`<release>21</release>`), Spring Boot
  BOM, centralized dependency and plugin management.
- `common/` — module skeleton. Empty of domain logic; exists so later phases
  have somewhere to put shared event schemas.
- `infra/docker-compose.yml` — PostgreSQL, Redis, Kafka for local development.
- Quality gate configs: `config/checkstyle.xml`, `config/spotbugs-exclude.xml`,
  Spotless/JaCoCo/Enforcer plugin declarations in the parent POM.
- `.github/workflows/ci.yml` — build, test, quality gates. **JDK pinned
  explicitly** (local machine runs JDK 26; the build targets 21).
- `.gitignore`, `.gitattributes`, `.github/pull_request_template.md`.
- Repository initialised, GitHub remote created, `main` branch protected.

### Done when

- [x] `mvn clean verify` passes from a clean clone.
- [x] A deliberate formatting violation fails the build.
- [x] `docker compose up` gives working Postgres, Redis, and Kafka.
- [x] CI runs on every PR and blocks merge on failure.
- [x] Direct pushes to `main` are rejected.
- [x] The build commands in `CLAUDE.md` have been **run and verified**, and the
      pending-scaffolding caveat is removed.

### Git

Branch `feature/phase-0-foundation` → PR → squash-merge → tag **`v0.1.0`**.

---

## Phase 1 — Control plane core

The first genuinely useful service: rules can be created, versioned, and stored.

### What gets built

**`rule-service/`** — new module, the first real service.

- `RuleController` — REST endpoints under `/api/v1/rules`, including the version
  sub-resources (`GET /rules/{id}/versions`, `GET /rules/{id}/versions/{n}`).
- `RuleService` — business logic, transaction boundaries, version increment.
- `RuleRepository`, `RuleVersionRepository` — persistence.
- Flyway migrations creating `rules` (identity + `current_version` pointer) and
  `rule_versions` (immutable history, PK `(rule_id, version)`).
- `GlobalExceptionHandler` — RFC 7807 `ProblemDetail` responses.
- Bucket4j rate-limit filter with Redis-backed counters.
- Tests: unit tests for version increment logic; Testcontainers integration
  tests for the full lifecycle; a concurrency test proving two simultaneous
  updates cannot produce duplicate version numbers.

**Decided:** optimistic locking (`@Version` + conditional update with bounded,
jittered retry) for the version increment — see
[ADR 0008](adr/0008-optimistic-locking-for-rule-versions.md). The `lock_version`
column ships in the first migration; the retry logic and the concurrency test
that validates the choice belong to `feature/phase-1-versioning`.

### Done when

- [x] Full rule lifecycle works via REST, with validation rejecting bad input.
- [x] Updating a rule creates a new version; prior versions remain retrievable
      via the versions sub-resource, and no `UPDATE` ever overwrites a stored
      rule's values.
      *(`PUT /rules/{id}` appends a row and moves the pointer;
      `noStoredVersionRowIsEverOverwrittenByAnUpdate` asserts against the
      database rather than the API, so the claim is about what is stored.)*
- [x] The concurrency test passes: parallel updates never duplicate a version.
      *(`RuleVersionConcurrencyIntegrationTest` fires 8 latch-released writers
      at one rule. Verified non-vacuous: with retry disabled, 7 of 8 writers
      lose the race, so the collisions are real and the retry is what converts
      them into successes.)*
- [x] Integration tests run against Testcontainers PostgreSQL.
- [x] Bucket4j throttles a test endpoint, and the limit holds across two running
      instances sharing Redis.
      *(`DistributedRateLimitIntegrationTest` spends one budget across two
      Spring contexts on separate ports; both then refuse. The limiter fails
      open when Redis is stopped — see the Phase 4 hardening table.)*

### Git

Branches `feature/phase-1-rule-crud`, `feature/phase-1-bucket4j`,
`feature/phase-1-versioning` → one PR each → tag **`v0.2.0`**.

Bucket4j landed before versioning, swapping the order originally planned here.
The two are independent — the rate-limit filter guards the API surface and never
touches version-increment logic — so the swap costs nothing. Versioning was the
last Phase 1 branch, and ADR 0008 is now proven by a passing concurrency test
rather than merely decided. All exit criteria are met and **`v0.2.0` is tagged**
(2026-08-19).

---

## Phase 2 — Event backbone

Services stop calling each other and start reacting to events. **This is where
the Deployment and Audit services are built.**

### What gets built

**`common/`** — filled in for the first time.

- Event envelope record: `eventId`, `eventType`, `occurredAt`, `traceId`,
  versioned payload.
- The seven event records from `architecture.md` (`RULE_CREATED` …
  `DEPLOYMENT_FAILED`).
- Shared error types.

**`rule-service/`** — extended, not rebuilt.

- `outbox` table + Flyway migration.
- `OutboxPublisher` — polls the outbox and publishes to Kafka, so no publish
  ever happens inside a database transaction.

**`deployment-service/`** — **new module.**

- `RuleEventConsumer` — consumes rule events from Kafka.
- `DeploymentService` — turns a rule version into a configuration, tracks status.
- `processed_events` table — idempotency, keyed on `eventId`.
- `deployments` table — status, target `(ruleId, version)`, config version.
- `DeploymentController` — `POST /api/v1/deployments` (accepts
  `Idempotency-Key`), `GET /api/v1/deployments/{id}`, rollback endpoint taking
  an explicit prior version.
- Retry with exponential backoff + jitter; dead-letter topic handling.

**`audit-service/`** — **new module.**

- `AuditEventConsumer` — consumes the same rule events independently.
- `audit_log` table — who changed what, from which value to which value.
- `GET /api/v1/audit` — queryable history.

### Done when

- [ ] A rule change produces an event that both consumers process independently.
- [ ] Replaying the same event twice provably changes nothing (tested).
- [ ] A failed deployment retries with backoff, then dead-letters.
- [ ] A deployment can be rolled back to a prior rule version.
- [ ] Killing a consumer mid-batch loses no events on restart.
- [ ] No Kafka publish occurs inside a database transaction (outbox verified).

### Git

Branches `feature/phase-2-outbox`, `feature/phase-2-deployment-service`,
`feature/phase-2-audit-service`, `feature/phase-2-idempotency` → PR each →
tag **`v0.3.0`**.

---

## Phase 3 — Real data plane

Enforcement moves out of the application and into infrastructure. **This is
where the simulated business services are built.**

### What gets built

**`simulators/`** — **three new modules**, built from one shared template.

All three are deliberately minimal and near-identical. Build the first one, then
copy its shape:

- One Spring Boot application class.
- One controller with 2–3 endpoints (`orders-service`: `GET /orders`,
  `POST /orders`; `payments-service`: `GET /payments`, `POST /payments`;
  `inventory-service`: `GET /inventory/{id}`).
- In-memory state only — **no database, no Kafka, no Redis.**
- Artificial latency and a configurable error rate, so traffic looks realistic.
- **Zero awareness of the platform.** No rule logic, no throttling code, no
  platform dependencies. If a simulator imports anything from `common/`, that is
  a design error — they are the test environment, not part of the system.

Each is roughly 100 lines. They are traffic targets, not engineering exercises;
resist making them interesting.

**`infra/envoy/`** — **new.**

- Base Envoy configuration; three clusters, one per simulator.
- Rate-limit filter configuration.
- Access-log format emitting allow/throttle events to Kafka.

**`deployment-service/`** — extended.

- `EnvoyConfigGenerator` — renders stored rule versions into real Envoy config.
- Config push via Envoy's admin API or xDS, applied without dropping in-flight
  requests.

**`analytics-service/`** — **new module.**

- `TrafficEventConsumer` — consumes `REQUEST_ALLOWED` / `REQUEST_THROTTLED`.
- Aggregation: request rate, throttle rate, error rate, quota utilization.
- `GET /api/v1/analytics/*` query endpoints.

**Traffic insights.** Raw rates are the input, not the output. The endpoints must
answer the questions a service owner actually asks — which is a different design
problem from computing an average:

- Which services and endpoints are throttled most?
- What percentage of traffic is throttled, and how has it moved over time?
- Which quotas are frequently reached, and which rules are **rarely or never
  used**? An unused rule is either dead configuration or a limit set so high it
  is not a limit.
- **What happened after a rule change?** Correlating a `(ruleId, version)`
  deployment against the throttle rate before and after is the query that makes
  the control plane explicable — and the one that needs immutable rule versions
  to be answerable at all.
- Which services see the largest traffic spikes?

These shape the aggregation: answering "rarely used" needs a per-rule match
counter, and answering "after a rule change" needs deployment events joined to
traffic windows. Both are cheap if designed in now and expensive to retrofit.

**`infra/load/`** — traffic generator script producing sustained realistic load.

### Done when

- [ ] All simulator traffic flows through Envoy.
- [ ] A rule created via the API measurably throttles live traffic end-to-end.
- [ ] Config updates apply without dropping in-flight requests.
- [ ] Analytics computes request/throttle/error rates from real traffic.
- [ ] Analytics answers the traffic-insight questions above — including
      identifying an unused rule and showing throttle rate before versus after a
      specific rule version was deployed.
- [ ] No simulator has any dependency on platform modules.

### Git

Branches `feature/phase-3-simulators`, `feature/phase-3-envoy`,
`feature/phase-3-config-generation`, `feature/phase-3-analytics` → PR each →
tag **`v0.4.0`**.

---

## Phase 4 — Observability and hardening

Make the running system explainable, then deliberately break it. No new services
— every existing module is instrumented, then subjected to failure.

Observability comes first for a reason: injecting a failure into a system you
cannot observe teaches nothing. You need the trace and the dashboard to see what
the failure actually did.

### What gets built

- Micrometer instrumentation in all services; metric names following the
  `<service>_<subject>_<unit>` convention.
- `infra/prometheus/prometheus.yml` — scrape configuration.
- `infra/grafana/dashboards/` — traffic, throttling, and deployment dashboards
  as committed JSON, not hand-clicked.
- OpenTelemetry auto-instrumentation + explicit trace-ID propagation into Kafka
  event headers and back out on consume.
- Jaeger or Tempo added to `docker-compose.yml`.
- Structured JSON logging config (Logback) with trace-ID correlation.
- Real readiness probes reflecting dependency health, not static `200`s.

**`docs/failure-scenarios.md`** — the hardening catalogue. Every distributed
-systems property the platform claims is listed with the failure that would
disprove it, the **expected** behaviour, and the observed result.

**`infra/chaos/`** — scripts that inject each scenario reproducibly. A failure
you cannot re-run is an anecdote, not a test.

The scenarios, each written as a claim to be falsified:

| Injected failure | The claim under test |
| --- | --- |
| Kill `deployment-service` mid-deployment | Deployment resumes or fails cleanly on restart; no rule is left half-applied |
| Deliver the same deployment event twice | `processed_events` makes the replay a no-op |
| Deliver configuration versions **out of order** (v18 before v17) | The stale config is rejected; the newer version stays live |
| Take Redis down | Rate limiting fails **open**, the control plane stays up |
| Take Kafka down, then restore | Producers buffer via the outbox; consumers resume from committed offsets with no lost events |
| Kill a consumer mid-batch | Uncommitted messages are redelivered, not dropped |
| Sudden 10× traffic spike | Throttling holds; latency degrades gracefully rather than collapsing |
| Postgres connection exhaustion | Timeouts fire and the circuit breaker opens instead of queueing forever |

Each scenario states its **decided** behaviour beforehand — fail open or fail
closed, retry or reject. `coding-standards.md` is explicit that an undecided
answer is not defensible, and writing the expectation first is what turns an
outage into a test.

### Done when

- [ ] A single request is traceable from API → Kafka → Deployment → Envoy →
      simulator as one trace.
- [ ] Dashboards show throttle rate per service and endpoint.
- [ ] Logs are correlatable to traces by trace ID.
- [ ] Readiness reports unready while a dependency is down.
- [ ] No log line contains secrets or PII.
- [ ] Every scenario in `failure-scenarios.md` has been **run**, and the observed
      behaviour matches the documented expectation — or the gap is filed as a bug.
- [ ] Out-of-order configuration delivery provably cannot apply a stale rule
      version.
- [ ] Redis and Kafka outages degrade the platform without taking it down.

### Git

Branches `feature/phase-4-metrics`, `feature/phase-4-tracing`,
`feature/phase-4-dashboards`, `feature/phase-4-hardening` → PR each →
tag **`v0.5.0`**.

---

## Phase 5 — Production infrastructure

Run it the way it would actually be run — orchestrated, scaled, and deployed
without downtime.

**Target is local Kubernetes.** Every orchestration concept worth learning —
pods, deployments, ConfigMaps, Secrets, probes, HPA, service discovery, rolling
and blue/green deploys — is learnable on kind or minikube. Running the same
manifests against a managed cloud cluster teaches cloud-provider operations, not
distributed systems, and costs real money and real hours to debug.

### What gets built

- `Dockerfile` per service — multi-stage, non-root user, minimal base image.
- `infra/k8s/` — deployments, services, ConfigMaps, Secrets, HPA, per service.
- Real readiness and liveness probes wired to the health checks from Phase 4.
- Blue/green deployment configuration.
- `.github/workflows/deploy.yml` — image build/push pipeline.

### Done when

- [ ] The full platform runs on local Kubernetes.
- [ ] Services scale horizontally under generated load via HPA.
- [ ] A blue/green deploy completes with no dropped requests.
- [ ] A killed pod is rescheduled without traffic loss.

### Git

Branches `feature/phase-5-docker`, `feature/phase-5-k8s`,
`feature/phase-5-cicd` → PR each → tag **`v1.0.0`** (first
production-capable release).

---

## Phase 5b — AWS *(deferred, optional)*

**Explicitly deferred.** Taking the platform to EKS reproduces an architecture
that already works locally; it teaches AWS operations rather than the
distributed-systems concepts this project exists to practise. It is the largest
time investment with the smallest conceptual return, so it waits until the
platform is genuinely finished — and may reasonably never happen.

Nothing in phases 0–6 may depend on this. When it is picked up:

- `infra/terraform/` — VPC, EKS, RDS PostgreSQL, MSK, ElastiCache, IAM, S3,
  CloudWatch, with a remote state backend.
- Per-service least-privilege IAM roles (IRSA).
- The same Kubernetes manifests from Phase 5, applied to a managed cluster.

### Done when

- [ ] The full platform runs on EKS.
- [ ] All infrastructure is Terraform-managed and reproducible from scratch.
- [ ] No service holds broader IAM permissions than it uses.

### Git

Branches `feature/phase-5b-terraform`, `feature/phase-5b-eks` → PR each →
tag **`v1.2.0`** (after the AI layer, since this is optional and out of line).

---

## Phase 6 — AI layer

Only now, on top of a platform that works.

### What gets built

**`agents/`** — new module or separate service.

- **Analytics Agent** — interprets traffic patterns, explains anomalies.
- **Rule Agent** — proposes rule changes from observed behaviour. Proposals
  only; never applies without human approval.
- **Incident Agent** — diagnoses failures across services and traces.
- MCP tool definitions wrapping control-plane APIs — the *only* access path
  agents get. No direct database or Kafka access.
- Agent harness: fixed scenario set, scoring, regression tracking.
- Agent actions flow through the same audit trail as human changes.

### Done when

- [ ] Agents read platform state only through defined tools.
- [ ] The Rule Agent's proposals require human approval before applying.
- [ ] The harness scores agent behaviour on a fixed scenario set, repeatably.
- [ ] Agent actions appear in the audit log, attributed to the agent.

### Git

Branches `feature/phase-6-*` → PR each → tag **`v1.1.0`**.
