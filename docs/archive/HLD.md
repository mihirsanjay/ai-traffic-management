> **Archived — superseded.** This is the original planning transcript from which
> the project docs were derived. It is kept for provenance and original
> reasoning only. The current source of truth is `docs/architecture.md`,
> `docs/tech-stack.md`, `docs/coding-standards.md`, and `docs/roadmap.md`.
> Where this file and those disagree, those win.

---

Yes. Let's lock down the architecture **before we start coding**. And there are a few things on your list that I would treat as supporting technologies rather than forcing into the core system.

## 1. High-level overview

We're building a **small-scale, production-style traffic management/control-plane platform**.

The idea:

> A service owner can define throttling rules for their APIs, deploy those rules to a distributed traffic layer, monitor how those rules perform, and eventually use AI agents to analyze and operate the platform.

The core flow is:

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

Meanwhile:

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

And **AI comes last**, sitting on top of the completed platform.

---

# 2. What services are we building?

I'd start with **5 core application services**.

### 1. Rule Management Service

**Purpose:** The main control-plane API.

Responsible for:

* Creating throttling rules
* Updating rules
* Deleting rules
* Validating rules
* Versioning rules
* Storing rules in PostgreSQL

Example:

```text
POST /rules

{
  "service": "orders",
  "endpoint": "/orders",
  "limit": 1000,
  "window": "1m"
}
```

---

### 2. Deployment Service

**Purpose:** Turn a stored rule into an actual deployed configuration.

It handles:

* Deployment requests
* Deployment status
* Configuration versions
* Retries
* Rollbacks
* Idempotency

Eventually:

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

This is one of the **most important services for your AWS internship preparation**.

---

### 3. Analytics Service

**Purpose:** Understand what's happening to the traffic.

It consumes events such as:

```text
REQUEST_ALLOWED
REQUEST_THROTTLED
DEPLOYMENT_SUCCEEDED
DEPLOYMENT_FAILED
```

and calculates things like:

* Request rate
* Throttle rate
* Error rate
* Latency
* Quota utilization
* Historical trends

---

### 4. Notification/Audit Service

Initially this can be relatively small.

It demonstrates that multiple consumers can independently react to Kafka events.

For example:

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

Audit records:

> User X changed Orders quota from 100 → 500.

Later we can use it for notifications.

---

### 5. Simulated Business Services

These are **not really part of the traffic-management platform**.

They're our test environment.

We'll create:

```text
Orders Service
Payments Service
Inventory Service
```

Each is a small Spring Boot application exposing REST APIs.

Their job is simply to generate realistic traffic targets.

For example:

```text
GET /orders
POST /orders

GET /payments
POST /payments

GET /inventory/{id}
```

We'll generate traffic against them and watch the platform manage it.

---

# 3. Are we including everything?

### Yes — but not everything gets equal importance.

Here's how I'd map your list.

| Technology / Concept     | Include?                   | Role                                       |
| ------------------------ | -------------------------- | ------------------------------------------ |
| Java                     | **YES**                    | Primary language                           |
| Spring Boot              | **YES**                    | Backend framework                          |
| REST APIs                | **YES**                    | Control-plane APIs                         |
| PostgreSQL               | **YES**                    | Persistent rule/config data                |
| Redis                    | **YES**                    | Distributed state/cache/rate-limit support |
| Bucket4j                 | **YES, Phase 1**           | Learn rate limiting                        |
| Envoy                    | **YES, later**             | Realistic data-plane                       |
| Kafka                    | **YES**                    | Pub/sub + event backbone                   |
| Microservices            | **YES**                    | Core architecture                          |
| Configuration management | **YES**                    | Major part of project                      |
| Docker                   | **YES**                    | Containerization                           |
| Kubernetes               | **YES**                    | Orchestration/scaling                      |
| AWS                      | **YES**                    | Production deployment                      |
| Terraform                | **YES**                    | Infrastructure as code                     |
| IAM                      | **YES**                    | AWS security                               |
| ECS/EKS                  | **YES**                    | We'll use **EKS**                          |
| S3                       | **Probably**               | Supporting AWS component                   |
| DynamoDB                 | **Probably not initially** | PostgreSQL already covers persistence      |
| CloudWatch               | **YES**                    | AWS observability                          |
| Micrometer               | **YES**                    | Application metrics                        |
| Prometheus               | **YES**                    | Metrics collection                         |
| Grafana                  | **YES**                    | Dashboards                                 |
| OpenTelemetry            | **YES**                    | Tracing                                    |
| Structured logging       | **YES**                    | Operations                                 |
| Health checks            | **YES**                    | Production readiness                       |
| CI/CD                    | **YES**                    | GitHub Actions initially                   |
| Blue/green deployment    | **YES, later**             | Deployment strategy                        |
| JUnit                    | **YES**                    | Testing                                    |
| Testcontainers           | **YES**                    | Realistic integration tests                |
| Caching                  | **YES**                    | Redis                                      |
| Service discovery        | **YES**                    | Kubernetes service discovery               |
| Horizontal scaling       | **YES**                    | Kubernetes                                 |
| Idempotency              | **YES**                    | Deployment APIs/events                     |
| Retries                  | **YES**                    | Distributed reliability                    |
| Circuit breakers         | **YES**                    | Resilience                                 |
| Pub/Sub                  | **YES**                    | Kafka                                      |
| AI agents                | **YES, LAST**              | Platform intelligence                      |
| Multi-agent system       | **YES, LAST**              | Agent layer                                |
| Agent harness            | **YES, LAST**              | Evaluation/testing                         |
| MCP/tool calling         | **YES, LAST**              | Agent integration                          |

### One important omission

I would add:

**OpenTelemetry + Jaeger/Tempo**

because distributed tracing is extremely useful once you have:

```text
API
 → Kafka
 → Deployment Service
 → Envoy
 → Business Service
```

You want to be able to follow one request across that entire system.

---

# The final architecture

Eventually we're aiming for something roughly like:

```text
                    ┌───────────────────┐
                    │   Service Owner   │
                    └─────────┬─────────┘
                              │
                         REST APIs
                              │
                    ┌─────────▼─────────┐
                    │  Rule Management   │
                    │      Service       │
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

And all of this eventually runs:

```text
Docker
    ↓
Kubernetes
    ↓
AWS EKS
    ↓
Terraform-managed infrastructure
```

Then **only after this entire system works**, we add:

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

### The key principle

We're **not going to force every technology into the project just to put it on a resume.**

Each technology should solve a problem.

For example:

**Kafka** → decouple asynchronous events.

**Redis** → fast distributed state/cache.

**PostgreSQL** → durable source of truth.

**Kubernetes** → run and scale services.

**Prometheus** → collect metrics.

**Grafana** → visualize them.

**OpenTelemetry** → trace distributed requests.

**Terraform** → reproducibly create AWS infrastructure.

**Envoy** → provide a realistic traffic/data plane.

**AI agents** → eventually operate on top of all the infrastructure we've built.

That way, by the end, you don't just know *how to use* these technologies—you understand **why a real engineering organization would choose them.**
