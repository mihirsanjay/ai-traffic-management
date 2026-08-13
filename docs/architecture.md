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
      ▼
Kafka / Pub-Sub
      │
      ▼
Deployment / Configuration System
      │
      ▼
Traffic Layer (Envoy)
      │
      ├────────► Orders Service
      ├────────► Payments Service
      └────────► Inventory Service
```

## Data-plane / observability flow

```text
Traffic
   │
   ▼
Envoy
   │
   ├── allowed
   └── throttled
          │
          ▼
      Metrics/Events
          │
     ┌────┴─────┐
     ▼          ▼
Prometheus    Kafka
     │          │
     ▼          ▼
 Grafana     Analytics
```

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

### 3. Analytics Service

Consumes traffic and deployment events to answer "what is actually happening":
request rate, throttle rate, error rate, latency, quota utilization, and
historical trends.

### 4. Notification / Audit Service

Small at first. Its architectural purpose is to demonstrate that multiple
consumers react independently to the same event stream:

```text
RULE_UPDATED
     │
     ▼
Kafka
 ┌───┴──────────┐
 ▼              ▼
Deployment     Audit
Service        Service
```

Audit records answer questions like *"User X changed the Orders quota from 100
to 500."* Notification behaviour comes later.

### 5. Simulated business services

**These are not part of the traffic-management platform.** They are the test
environment — realistic traffic targets so the platform has something to manage.

Three small Spring Boot applications:

```text
GET /orders          GET /payments          GET /inventory/{id}
POST /orders         POST /payments
```

Traffic is generated against them and the platform is observed managing it.

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
                         PostgreSQL
                              │
                              ▼
                           Kafka
                    ┌─────────┼─────────┐
                    │         │         │
                    ▼         ▼         ▼
               Deployment  Analytics   Audit
                 Service    Service   Service
                    │
                    ▼
              Configuration
                 System
                    │
                    ▼
                  Envoy
              ┌─────┼─────┐
              ▼     ▼     ▼
           Orders Payments Inventory
              │     │     │
              └─────┼─────┘
                    │
                 Metrics
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
     Prometheus          OpenTelemetry
          │                   │
          ▼                   ▼
       Grafana          Trace Backend
```

Running on:

```text
Docker → Kubernetes → AWS EKS → Terraform-managed infrastructure
```

Only after the platform works end-to-end does the AI layer go on top:

```text
                 AI Layer
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
    Analytics     Rule       Incident
      Agent       Agent       Agent
        │           │           │
        └─────── Tools/APIs ────┘
                    │
                    ▼
              Control Plane
```

## Repository layout

Maven monorepo, multi-module, single parent build:

```text
ai-traffic-management/
  pom.xml                     parent: dependency + plugin management
  common/                     shared event schemas, DTOs, error types
  rule-service/               Rule Management Service
  deployment-service/         Deployment Service
  analytics-service/          Analytics Service
  audit-service/              Notification / Audit Service
  simulators/
    orders-service/
    payments-service/
    inventory-service/
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

| Event                  | Producer         | Primary consumers    |
| ---------------------- | ---------------- | -------------------- |
| `RULE_CREATED`         | Rule Management  | Deployment, Audit    |
| `RULE_UPDATED`         | Rule Management  | Deployment, Audit    |
| `RULE_DELETED`         | Rule Management  | Deployment, Audit    |
| `DEPLOYMENT_SUCCEEDED` | Deployment       | Analytics, Audit     |
| `DEPLOYMENT_FAILED`    | Deployment       | Analytics, Audit     |
| `REQUEST_ALLOWED`      | Data plane       | Analytics            |
| `REQUEST_THROTTLED`    | Data plane       | Analytics            |

Conventions:

- **Topic naming** — `<domain>.<entity>.<event-type>`, e.g. `control.rule.updated`,
  `traffic.request.throttled`.
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
