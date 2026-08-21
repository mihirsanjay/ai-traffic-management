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

> **Rescoped 2026-08-20.** This roadmap was condensed after Phase 1 in favour of
> **breadth over depth**: finishing the core platform in three sessions and then
> deploying it to a real cloud with CI/CD and secrets management. The audit and
> analytics services are dropped ([ADR 0009](adr/0009-drop-audit-and-analytics-services.md)),
> the test suite is thinned ([ADR 0011](adr/0011-thinned-test-strategy.md)), the
> AI layer is benched, and cloud deployment is promoted from an optional
> appendix to Phase 6. Everything cut is recorded in [deferred.md](deferred.md)
> with what it would have taught and the cost of adding it back. Phases 0 and 1
> below are unchanged history.

## Module introduction map

Which phase first creates each module — the answer to "when do we actually build
the Deployment Service?"

| Module                            | Introduced in |
| --------------------------------- | ------------- |
| `pom.xml` (parent), `common/`      | Phase 0 (skeleton), Phase 2 (event contracts) |
| `infra/`                           | Phase 0       |
| `rule-service/`                    | Phase 1       |
| `deployment-service/`              | Phase 2       |
| `simulators/{orders,payments}-service/` | Phase 3  |
| `infra/envoy/`                     | Phase 3       |
| `infra/k8s/`                       | Phase 5       |
| `infra/terraform/`                 | Phase 6       |
| `agents/`                          | Phase 7 *(benched)* |

Dropped from the original map: `audit-service`, `analytics-service`,
`simulators/inventory-service`, and `infra/chaos/`. See [deferred.md](deferred.md).

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
      open when Redis is stopped — proven by `RateLimitFailOpenIntegrationTest`.)*

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

Rule changes stop being private to `rule-service` and become events. **This is
where the Deployment Service is built.**

### What gets built

**`common/`** — filled in for the first time.

- `EventEnvelope<T>` — `eventId`, `eventType`, `occurredAt`, `traceId`, payload.
- `RuleChangedPayload` — covers created, updated, and deleted; the event type and
  the topic distinguish them, so one record serves all three.
- `DeploymentOutcomePayload`, `EventType`, `Topics`.
- Jackson annotations only. **No Spring, no Kafka on `common`'s classpath** — the
  Phase 3 simulators must be able to ignore this module entirely (ADR 0003).

**`rule-service/`** — extended, not rebuilt.

- `outbox` table + Flyway migration. One nullable `published_at` is the whole
  state machine; no attempt counters or error columns.
- `deleted_by` column and a `softDelete(ruleId, deletedBy)` signature, so a
  `RULE_DELETED` event can say who deleted it.
- `OutboxWriter` — joins the caller's transaction, deliberately un-annotated.
- `OutboxPublisher` — scheduled poll claiming batches with
  `FOR UPDATE SKIP LOCKED`, publishing keyed by rule ID, marking published after
  the send returns.

**`deployment-service/`** — **new module.**

- `RuleEventConsumer` — consumes the three `control.rule.*` topics.
- `processed_events` table — idempotency keyed on `eventId`.
- `deployments` table — status, target `(ruleId, version)`, config version, and
  the rule's targeting denormalised so the Envoy route table can be assembled
  without calling back to `rule-service`.
- `DeploymentController` — `GET /deployments/{id}`, `GET /deployments?ruleId=`,
  and `POST /deployments/{ruleId}/rollback` taking an explicit prior version.
  Rollback appends a new deployment row rather than mutating one.
- `ConfigWriter` interface with a no-op implementation; Phase 3 swaps in the real
  Envoy writer.
- `DefaultErrorHandler` with a fixed backoff and a dead-letter recoverer — about
  eight lines, enough that a poison message does not block its partition.

### Done when

- [x] Creating, updating, and deleting a rule each write an outbox row in the
      same transaction as the state change.
      *(Create writes after its try/catch so an outbox failure is not misreported
      as a 409; update writes inside `RuleVersionAppender`, which owns the
      transaction, rather than in the deliberately untransacted `update()`.)*
- [x] No Kafka publish occurs inside a database transaction, proven by a test
      that a rolled-back rule change leaves no outbox row.
      *(`OutboxTransactionIntegrationTest` forces a failure after the version row
      is written and asserts the event rolled back with it. This is the one
      property that looks identical in review whether or not it holds.)*
- [x] Events reach `control.rule.*` keyed by rule ID, and rows are marked
      published.
      *(`OutboxSkipLockedIntegrationTest` additionally proves two concurrent
      pollers claim disjoint batches, which is what keeps `rule-service`
      scalable beyond one replica.)*
- [x] `deployment-service` consumes all three topics and records a deployment.
- [x] Delivering the same event twice produces exactly one deployment row.
      *(`RuleEventConsumerIntegrationTest` asserts row identity and timestamps,
      not just the count - an upserting consumer would hold the count at one
      while rewriting the row. This test found three real bugs: idempotency that
      silently did not work, a dead-letter topic naming mismatch that would have
      failed against real infrastructure, and a redelivery loop caused by
      catching a constraint violation inside a transaction Postgres had already
      marked rollback-only.)*
- [x] A deployment can be rolled back to a prior rule version.
      *(Rollback appends a new deployment rather than rewriting history, so the
      record reads as what actually happened.)*
- [x] `mvn clean verify` is green with the coverage floor at 0.60.
      *(Coverage: `common` 90%, `rule-service` 91.9%, `deployment-service`
      84.8%.)*

### Git

Branches `feature/phase-2-outbox`, `feature/phase-2-deployment-service` → one PR
each → tag **`v0.3.0`**.

The roadmap restructure landed first as its own PR, so the code was built against
documentation that matched it. All exit criteria are met and **`v0.3.0` is
tagged** (2026-08-21).

---

## Phase 3 — Real data plane

Enforcement moves out of the application and into infrastructure. **This is where
the simulated business services are built.**

### What gets built

**`simulators/`** — **two new modules**, `orders-service` and `payments-service`.

Roughly 80 lines each: one application class, one controller with two endpoints,
in-memory state only. Artificial latency and a configurable error rate, defaulting
to zero so tests stay deterministic.

**Zero awareness of the platform.** No rule logic, no throttling code, no
dependency on `common/`. If a simulator imports anything from the platform, that
is a design error — they are the test environment, not part of the system.

**`infra/envoy/`** — **new.**

- `bootstrap.yaml` with `dynamic_resources` pointing at a watched xDS directory.
- `xds/lds.yaml` — listener, HTTP connection manager, the `local_ratelimit`
  filter registered with no global bucket, and a JSON access log to stdout.
- `xds/cds.yaml` — one cluster per simulator.
- `xds/rds.yaml` — the route table, and **the only file the control plane
  rewrites**.

Enforcement uses Envoy's **`local_ratelimit` filter with per-route token buckets
delivered by filesystem xDS** — no external rate-limit service and no xDS gRPC
server. The decision and its four sharp edges are recorded in ADR 0010.

**`deployment-service/`** — extended.

- `EnvoyConfigWriter` — renders deployments into a route configuration and
  installs it by **atomic move**, because Envoy's watcher fires on moves, not
  writes.
- `EnvoyAdminClient` — polls the admin API to confirm the new `version_info` went
  live. Filesystem xDS has no ACK/NACK, so without this a rejected config would
  be reported as a successful deployment.

### Done when

- [ ] Both simulators serve traffic and depend on no platform module.
- [ ] All simulator traffic flows through Envoy.
- [ ] A rule created via the API measurably throttles live traffic end-to-end:
      the request after the limit returns 429.
- [ ] Config updates apply without restarting Envoy and without dropping
      in-flight requests.
- [ ] `deployment-service` confirms the applied version via the admin API before
      reporting success.
- [ ] A rollback restores the prior limit, observably at the proxy.

### Git

Branches `feature/phase-3-simulators`, `feature/phase-3-envoy` → one PR each →
tag **`v0.4.0`**.

---

## Phase 4 — Close the core

Make the running system explainable. No new services — the existing ones become
observable, the loose ends from Phases 2 and 3 get closed, and the documentation
is brought back in line with what was built.

This phase is deliberately light on code. Phase 3 is the first time every service
runs together, which is where integration gaps surface; leaving room for them is
realistic, assuming they will not exist is not.

### What gets built

- Structured JSON logging in every service, via Logback's built-in encoder.
- A **correlation ID** carried from an inbound request through the outbox into a
  Kafka header and restored on consume. This is not distributed tracing — it is
  the cheap 40-line version of the property that matters, and tracing proper is
  deferred.
- Five Micrometer metrics, named `<service>_<subject>_<unit>`:
  `rule_service_outbox_pending` (the single most useful number in the system — a
  rising value means the publisher is stuck), `rule_service_outbox_published_total`,
  `deployment_service_deployments_total{status}`,
  `deployment_service_events_duplicate_total`, and
  `deployment_service_envoy_apply_seconds`.
- Real readiness probes reflecting genuine dependency health, not static `200`s.
- Documentation closeout: `deferred.md` finalised, `learning-map.md` written.

Envoy's own `local_ratelimit` counters expose allowed and throttled request rates
on its admin endpoint for free — no work beyond documenting where to look.

**Deliberately not built:** OpenTelemetry, a trace backend, Prometheus, Grafana,
and the failure-injection catalogue. All are recorded in [deferred.md](deferred.md)
with their trigger to revisit.

### Done when

- [ ] Every service emits structured JSON logs carrying a correlation ID that
      survives the Kafka boundary.
- [ ] Readiness reports unready while a dependency is down, verified by stopping
      one.
- [ ] The five metrics are exposed and non-zero after an end-to-end run.
- [ ] No log line contains secrets or PII.
- [ ] Every roadmap cut appears in `deferred.md`.
- [ ] `mvn clean verify` is green.

### Git

Branches `feature/phase-4-observability`, `docs/phase-4-closeout` → PR each → tag
**`v0.5.0`**. **The core application is complete at this tag.**

---

## Phase 5 — Containers and local Kubernetes

Run it the way it would actually be run — orchestrated, before it is remote.
Local Kubernetes first, because debugging manifests and cloud IAM simultaneously
is the expensive failure mode.

### What gets built

- `Dockerfile` per service — multi-stage, non-root, minimal base image. The two
  simulators share one parameterised file.
- `infra/k8s/` — deployments, services, ConfigMaps, and Secrets per service.
- Probes wired to the health checks from Phase 4.

**Three constraints to design for, all knowable in advance:**

1. Envoy's xDS directory must be **writable by `deployment-service` and readable
   by Envoy**. A ConfigMap cannot serve this — the control plane writes it. Envoy
   runs as a **sidecar in the `deployment-service` pod** sharing an `emptyDir`,
   which also keeps the atomic move on one filesystem.
2. `watched_directory` is already set in the bootstrap from Phase 3. Without it,
   file-based xDS silently stops reloading under Kubernetes, because ConfigMap
   updates swap a symlink rather than moving the file.
3. `replicas: 1` for `deployment-service` (single xDS writer) and Envoy
   (rate-limit buckets are per process). `rule-service` **can** scale, because
   the outbox poller claims rows with `SKIP LOCKED`. Document the reason in the
   manifests — an unexplained `replicas: 1` reads as an oversight.

### Done when

- [ ] The full platform runs on local Kubernetes.
- [ ] A killed pod is rescheduled and traffic recovers.
- [ ] The end-to-end throttling path works through the cluster.

### Git

Branches `feature/phase-5-docker`, `feature/phase-5-k8s` → PR each → tag
**`v0.9.0`**.

---

## Phase 6 — Cloud, CI/CD, and secrets

The platform runs somewhere real, deploys itself, and stops keeping secrets in
plaintext.

This phase **promotes what was previously the deferred Phase 5b**. The original
argument for deferring it — that cloud teaches provider operations rather than
distributed systems — was about sequencing under a fixed budget, and it set the
condition that it "waits until the platform is genuinely finished." `v0.5.0` is
that condition.

### What gets built

- `infra/terraform/` — cloud resources only: network, managed Kubernetes, managed
  PostgreSQL, secret storage, and service identities. **Kubernetes objects stay
  as YAML**; managing them through Terraform trades Kubernetes skills for
  Terraform state-drift debugging.
- Secrets in the provider's secret manager, synced into the cluster by an
  operator bound to a workload identity. Not base64 Kubernetes Secrets, which are
  not encryption.
- `.github/workflows/release.yml` — build and push images on tag.
- `.github/workflows/deploy.yml` — deploy via **workload identity federation**,
  not a long-lived service-account key in repository secrets.

**Cost shapes the choices here.** Managed Kubernetes control planes differ by
roughly $70/month between providers; managed Kafka is the single most expensive
line item and is worth self-hosting in-cluster at this throughput; managed
PostgreSQL is cheap and worth paying for. Redis stays in-cluster — paying for
managed durability of a cache the platform deliberately fails open on is
incoherent.

**Provider is an open decision**, to be recorded as ADR 0012 when it is made.

### Done when

- [ ] The full platform runs on managed Kubernetes in a cloud account.
- [ ] All cloud infrastructure is Terraform-managed and reproducible from scratch.
- [ ] Pushing a tag builds, pushes, and deploys without manual steps.
- [ ] No secret exists in the repository, and no long-lived cloud key exists in
      GitHub.
- [ ] No service holds broader permissions than it uses.

### Git

Branches `feature/phase-6-terraform`, `feature/phase-6-secrets`,
`feature/phase-6-cicd` → PR each → tag **`v1.0.0`** (first production-capable
release).

---

## Phase 7 — AI layer *(benched)*

**Benched 2026-08-20.** Deliberately not scheduled: the platform is being taken
to production first. The sequencing rule that put this phase last is unchanged —
agents operating a platform that does not work have nothing to operate — and
being last is exactly why it is the phase that gets postponed when scope is cut.

When it is picked up, the scope is deliberately smaller than originally planned:
one agent rather than three, MCP tool definitions wrapping the control-plane API
as the only access path, and human approval before any proposed rule change is
applied. The evaluation harness with fixed scenarios and regression scoring is
the part most likely to be worth building second.

No branches and no version tag are reserved.
