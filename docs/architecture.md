# Architecture

## The idea

A service owner defines throttling rules for their APIs, deploys those rules to
a distributed traffic layer, monitors how those rules perform, and — eventually
— uses AI agents to analyze and operate the platform.

The system splits along the standard control-plane / data-plane line:

- **Control plane** — where rules are authored, validated, versioned, and
  deployed. This is the platform.
- **Data plane** — where traffic actually flows and rules are enforced (Envoy).
- **Observability plane** — metrics, traces, and events emitted by both.

## Control-plane flow

```text
Service Owner
      │
      ▼
Control Plane APIs
      │
      │ create/update throttling rules
      ▼
Rule Management
      │
      │ PostgreSQL + outbox, one transaction
      ▼
Kafka / Pub-Sub
      │
      ▼
Deployment / Configuration System
      │
      │ writes an xDS route file, atomically
      ▼
Traffic Layer (Envoy)
      │
      ├────────► Orders Service
      └────────► Payments Service
```

## Data-plane flow

```text
Traffic
   │
   ▼
Envoy  ──── local_ratelimit token bucket, per route
   │
   ├── allowed    → upstream simulator
   └── throttled  → 429
          │
          ▼
   Envoy admin /stats
   (ok, rate_limited, enforced counters)
```

Throttle telemetry comes from Envoy's own counters rather than from an event
stream. The analytics service that would have aggregated `REQUEST_*` events is
dropped — see [ADR 0009](adr/0009-drop-audit-and-analytics-services.md).

## Services

### 1. Rule Management Service

The main control-plane API and the system's front door. Owns rule lifecycle:
creation, update, deletion, validation, and versioning, with PostgreSQL as the
durable source of truth.

```text
POST /rules

{
  "service": "orders",
  "endpoint": "/orders",
  "limit": 1000,
  "window": "1m"
}
```

Rules are versioned rather than mutated in place — deployment and rollback both
need to refer to a specific immutable version.

### 2. Deployment Service

Turns a stored rule into an actually deployed configuration. Owns deployment
requests, deployment status, configuration versions, retries, rollbacks, and
idempotency.

```text
Rule Management
       │
       ▼
     Kafka
       │
       ▼
Deployment Service
       │
       ▼
Envoy configuration
```

This is the service where distributed-systems concerns concentrate: a
deployment can partially fail, be retried, be delivered twice, or need to be
rolled back. Idempotency is a correctness requirement here, not a nicety.

### 3. Simulated business services

**These are not part of the traffic-management platform.** They are the test
environment — realistic traffic targets so the platform has something to manage.

Two small Spring Boot applications:

```text
GET /orders          GET /payments
POST /orders         POST /payments
```

Traffic is generated against them and the platform is observed managing it.
Two upstreams are enough to prove per-route configuration; a third would be
copy-paste. See [deferred.md](deferred.md).

## Target architecture

```text
                    ┌───────────────────┐
                    │   Service Owner   │
                    └─────────┬─────────┘
                              │
                         REST APIs
                              │
                    ┌─────────▼─────────┐
                    │  Rule Management  │
                    │      Service      │
                    └─────────┬─────────┘
                              │
                    PostgreSQL + outbox
                       (one transaction)
                              │
                              ▼
                           Kafka
                              │
                              ▼
                    ┌───────────────────┐
                    │    Deployment     │
                    │      Service      │
                    └─────────┬─────────┘
                              │
                     xDS route file
                       (atomic move)
                              │
                              ▼
                            Envoy
                        ┌─────┴─────┐
                        ▼           ▼
                     Orders     Payments
```

Kafka currently has one consumer. The fan-out capability is retained — topics,
partitioning by rule ID, and consumer groups all still support adding a consumer
without touching the producer, and `DEPLOYMENT_*` events are published with no
consumer at all — but the second and third consumers are not built. See
[ADR 0009](adr/0009-drop-audit-and-analytics-services.md).

Running on:

```text
Docker → local Kubernetes → managed Kubernetes, Terraform-managed
```

The AI layer is benched rather than cancelled; it would go on top of a platform
that already works, never beside one that does not.

## Repository layout

Maven monorepo, multi-module, single parent build:

```text
ai-traffic-management/
  pom.xml                     parent: dependency + plugin management
  common/                     shared event schemas, DTOs, error types
  rule-service/               Rule Management Service
  deployment-service/         Deployment Service
  simulators/
    orders-service/
    payments-service/
  infra/                      docker-compose, Envoy config, Terraform, k8s
  docs/
```

`common` holds only what genuinely crosses service boundaries — event schemas
above all. Resisting the urge to make it a dumping ground is what keeps the
services independently deployable.

## Event contracts

Events are the integration surface between services, so they are treated as a
public API: additive changes only, no field removal or type narrowing without a
new topic version.

| Event                  | Producer         | Consumers            |
| ---------------------- | ---------------- | -------------------- |
| `RULE_CREATED`         | Rule Management  | Deployment           |
| `RULE_UPDATED`         | Rule Management  | Deployment           |
| `RULE_DELETED`         | Rule Management  | Deployment           |
| `DEPLOYMENT_SUCCEEDED` | Deployment       | *(none yet)*         |
| `DEPLOYMENT_FAILED`    | Deployment       | *(none yet)*         |

The two deployment events are published deliberately despite having no consumer.
They are the standing demonstration that a producer does not know or care who is
listening — adding a consumer later requires no change to the producer. The
former `REQUEST_ALLOWED` and `REQUEST_THROTTLED` events are removed entirely:
they existed to feed the dropped analytics service, and Envoy has no native Kafka
sink to produce them.

Conventions:

- **Topic naming** — `<domain>.<entity>.<event-type>`, e.g. `control.rule.updated`,
  `control.deployment.succeeded`.
- **Envelope** — every event carries `eventId` (UUID), `eventType`,
  `occurredAt` (UTC instant), `traceId`, and a versioned `payload`.
- **Idempotency** — consumers key on `eventId`. Delivery is at-least-once, so
  every consumer must be safe to run twice on the same event. The Deployment
  Service additionally accepts a caller-supplied `Idempotency-Key` header on
  mutating endpoints.
- **Ordering** — partition by the entity the event concerns (e.g. rule ID) so
  per-entity ordering holds. Cross-entity ordering is not guaranteed and must
  not be relied on.

## Key decisions

- **Rules are versioned, never mutated.** Rollback means redeploying a prior
  version, which requires that prior versions still exist.
- **The write path is asynchronous.** Rule Management commits to PostgreSQL and
  publishes; it does not wait for deployment. Deployment status is queried
  separately.
- **The data plane is authoritative for enforcement.** Application-level rate
  limiting (Bucket4j, Phase 1) is a learning step and a fallback, not the
  long-term enforcement point.
- **Business services stay dumb.** They must never know the platform exists;
  otherwise the test environment stops being realistic.
