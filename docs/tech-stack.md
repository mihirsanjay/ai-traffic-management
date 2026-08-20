# Tech Stack

## The governing principle

Every technology here must solve a problem the project actually has. Nothing is
included to lengthen a resume. If a component cannot be justified by the
sentence *"we need this because X breaks without it"*, it does not belong.

The corollary matters just as much: understanding **why a real engineering
organization would choose a technology** is the point, not just operating it.

## Core platform

| Technology      | The problem it solves                                                     |
| --------------- | ------------------------------------------------------------------------- |
| **Java 21 LTS** | Primary language. Records, sealed types, pattern matching, virtual threads. |
| **Spring Boot 4.x** | Backend framework — DI, web layer, config, actuator health/metrics.   |
| **Maven**       | Multi-module build with centralized dependency and plugin management.     |
| **REST APIs**   | The control-plane interface service owners actually call.                 |
| **PostgreSQL**  | Durable source of truth for rules, versions, and deployment state.        |
| **Redis**       | Fast distributed state and caching; rate-limit counters shared across instances. |
| **Kafka**       | Decouples producers from consumers so services react to events independently. |

## Traffic enforcement

| Technology   | The problem it solves                                                        |
| ------------ | ---------------------------------------------------------------------------- |
| **Bucket4j** | Phase 1 only. In-application rate limiting, to learn the mechanics (token buckets, windows, distributed counters) before delegating enforcement. |
| **Envoy**    | The real data plane. A production-grade proxy enforcing throttling at the edge rather than inside each application. |

Bucket4j is deliberately temporary. It is superseded by Envoy in Phase 3, and
that transition — moving enforcement out of the application and into the
infrastructure — is itself one of the more instructive parts of the project.

## Observability

| Technology        | The problem it solves                                                  |
| ----------------- | ---------------------------------------------------------------------- |
| **Micrometer**    | Application metrics instrumentation, vendor-neutral.                   |
| **Prometheus** *(deferred)* | Scrapes and stores those metrics.                            |
| **Grafana** *(deferred)* | Dashboards over Prometheus data.                                |
| **OpenTelemetry** *(deferred)* | Distributed tracing across service and Kafka boundaries.  |
| **Trace backend** *(deferred)* | Stores and renders traces.                                |
| **Structured logging** | Machine-parseable logs, correlatable with traces by trace ID.     |
| **Health checks** | Kubernetes liveness/readiness; production readiness generally.         |
| **Cloud-native logging** | Provider observability once running in the cloud (Phase 6).     |

Tracing was the one genuine omission in the original plan, and the argument for
it still holds: with a request path that runs
`API → Kafka → Deployment Service → Envoy → business service`, following one
request across the whole system is the difference between debugging and guessing.

**It is nonetheless deferred** (2026-08-20, see [deferred.md](deferred.md)).
Phase 4 ships a correlation ID that survives the Kafka boundary — the cheap
version of the same property, and the seam a real tracer plugs into later.

## Infrastructure

| Technology       | The problem it solves                                                   |
| ---------------- | ----------------------------------------------------------------------- |
| **Docker**       | Containerization; reproducible local environment via docker-compose.     |
| **Kubernetes**   | Orchestration, scaling, service discovery, rolling deploys.              |
| **Managed Kubernetes** | The production target (Phase 6). Provider undecided — see ADR README. |
| **Terraform**    | Reproducible infrastructure as code.                                     |
| **IAM**          | AWS access control, least privilege.                                     |
| **Object storage** | Supporting role only — build artifacts and config snapshots.           |
| **GitHub Actions** | CI/CD: build, test, quality gates, image publishing.                  |

## Distributed-systems concerns

These are properties of the design rather than dependencies, but they are
first-class requirements:

- **Idempotency** — deployment APIs and every Kafka consumer.
- **Retries** — exponential backoff with jitter on all remote calls.
- **Circuit breakers** — prevent a slow dependency from consuming all threads.
- **Caching** — Redis, for rate-limit counters. With enforcement in Envoy there is no application read path left to cache.
- **Service discovery** — Kubernetes-native.
- **Horizontal scaling** — stateless services scale out; state lives in
  PostgreSQL, Redis, and Kafka.
- **Blue/green deployment** — deferred; see docs/deferred.md.

## Testing

| Technology         | The problem it solves                                                 |
| ------------------ | --------------------------------------------------------------------- |
| **JUnit 5**        | Unit and integration test framework.                                  |
| **Testcontainers** | Real PostgreSQL, Kafka, and Redis in integration tests instead of mocks or in-memory substitutes that behave differently from production. |

## AI layer — last, deliberately

| Technology              | The problem it solves                                          |
| ----------------------- | -------------------------------------------------------------- |
| **AI agents**           | Analyze and operate the platform: analytics, rule, and incident agents. |
| **Multi-agent system**  | Separate agents with separate responsibilities and tools.      |
| **MCP / tool calling**  | How agents invoke control-plane APIs safely.                   |
| **Agent harness**       | Evaluation and regression testing for agent behaviour.         |

The AI layer is built **only after the platform works end-to-end**. Agents that
operate a system that does not yet function have nothing to operate, and their
failures become indistinguishable from the platform's.

## Deferred and rejected

| Technology     | Decision              | Reasoning                                                   |
| -------------- | --------------------- | ----------------------------------------------------------- |
| **DynamoDB**   | Deferred              | PostgreSQL already covers persistence. Adding a second datastore without a distinct access pattern to justify it adds operational cost and no learning. Revisit only if a genuine high-throughput key-value need appears. |
| **ECS**        | Rejected in favour of Kubernetes | Kubernetes skills transfer across providers; a managed Kubernetes service is the orchestration target. |
