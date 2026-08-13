# Local Infrastructure

PostgreSQL, Redis, and Kafka for local development. Requires Docker only —
nothing else needs installing.

This is a **development environment, not a model of production.** Production
runs on EKS with RDS, ElastiCache, and MSK (Phase 5). Single-node, plaintext,
and replication-factor-1 are correct here and would be wrong there.

## Usage

```bash
docker compose -f infra/docker-compose.yml up -d      # start
docker compose -f infra/docker-compose.yml ps         # status
docker compose -f infra/docker-compose.yml logs -f    # follow logs
docker compose -f infra/docker-compose.yml down       # stop, keep data
docker compose -f infra/docker-compose.yml down -v    # stop, WIPE data
```

`up` is idempotent — re-running it on an existing stack is a no-op, and the
topic-creation job re-runs harmlessly.

## What you get

| Service    | Host address     | Credentials         |
| ---------- | ---------------- | ------------------- |
| PostgreSQL | `localhost:5432` | `traffic` / `traffic` |
| Redis      | `localhost:6379` | none                |
| Kafka      | `localhost:9092` | none (PLAINTEXT)    |

All three bind to `127.0.0.1` rather than `0.0.0.0`, so they are not exposed
to the local network. The credentials are throwaway and deliberately
committed — they guard nothing but a loopback container. Real secrets never
live in this repository.

### `atm-kafka-topics` shows as `Exited (0)` — that is success

It is a one-shot provisioning job: it creates the topics, prints them, and
exits. A non-zero exit is the failure case.

## Databases

One logical database per service, created on first startup by
`postgres/init/01-create-databases.sql`:

`rule_service` · `deployment_service` · `audit_service` · `analytics_service`

A single Postgres container is a local convenience; separate databases keep
it from becoming shared-database coupling between services.

**Schema is not created here.** Each service owns its schema and applies it
with Flyway at startup, so migrations are versioned in the service's own
source tree.

Init scripts run **only** on first initialization of an empty data
directory. To pick up an edit to them, you must wipe the volume:

```bash
docker compose -f infra/docker-compose.yml down -v
```

## Topics

Created by `kafka/create-topics.sh`, following the
`<domain>.<entity>.<event-type>` convention from
[architecture.md](../docs/architecture.md).

| Topic | Partitions | Retention |
| ----- | ---------- | --------- |
| `control.rule.created` / `.updated` / `.deleted` | 3 | 7d |
| `control.deployment.succeeded` / `.failed` | 3 | 7d |
| `traffic.request.allowed` / `.throttled` | 6 | 24h |
| `control.rule.*.dlt` (dead-letter) | 3 | 7d |

Traffic topics get more partitions because they carry one event per proxied
request; control-plane topics are low volume.

**Auto-creation is disabled.** Topic names are part of the event contract, so
a typo must fail loudly rather than silently creating a topic nobody consumes.
Adding a topic means adding it to `create-topics.sh`.

Partitioning is by the entity the event concerns (e.g. rule ID) — Kafka only
guarantees ordering *within* a partition, so cross-entity ordering must never
be relied on.

## Two Kafka listeners, deliberately

A broker advertises the address a client should use to reach it after the
initial metadata call. Clients on the host and clients in containers need
different addresses, so one listener would necessarily break one of them.

| Connecting from | Bootstrap server |
| --------------- | ---------------- |
| Host — your IDE, `mvn test`, local Spring Boot | `localhost:9092` |
| Another container on this compose network | `kafka:29092` |

## Configuration

Every value has a working default; `infra/.env` is optional and gitignored.
Copy it only if you need to change something — most often a port already in
use:

```bash
cp infra/.env.example infra/.env
```

`KAFKA_CLUSTER_ID` is the exception to "just change it": KRaft records the ID
in the log directory, and a mismatch makes the broker refuse to start. Change
it only together with `down -v`.

## Relationship to Testcontainers

Integration tests do **not** use this stack. Testcontainers starts its own
disposable containers per test run, so tests never depend on what is running
locally and never leave state behind that a later test could read.

This stack is for running services by hand, inspecting data, and manual
exploration. Testcontainers just needs the Docker daemon to be up.

## Troubleshooting

**Port already in use** — something else holds 5432/6379/9092. Either stop it
or set `POSTGRES_PORT` / `REDIS_PORT` / `KAFKA_PORT` in `infra/.env`.

**Kafka won't start after editing `KAFKA_CLUSTER_ID`** — the ID no longer
matches the one in the log directory. `down -v` and start fresh.

**A schema change in an init script isn't showing up** — init scripts only run
on a fresh volume. `down -v`.

**Testcontainers tests fail with a connection error** — the Docker daemon
isn't running. Start Docker Desktop.

**Health checks never go healthy** — inspect the cause directly:

```bash
docker compose -f infra/docker-compose.yml logs kafka
docker inspect --format '{{json .State.Health}}' atm-kafka
```
