# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in this repository.

## What this is

A small-scale, production-style **traffic management control plane**. A service
owner defines throttling rules for their APIs, those rules are deployed to a
distributed traffic layer (Envoy), and the resulting traffic is observed and
analyzed. An AI agent layer is added last, on top of the working platform.

**Repository:** https://github.com/mihirsanjay/ai-traffic-management
**Status: Phase 0 in progress.** The build, quality gates, and local
infrastructure work. Phase 1 (`rule-service/`) has not started.

## Tech stack

Java 21 LTS · Spring Boot 3.x · Maven (multi-module monorepo) · PostgreSQL ·
Redis · Kafka · Envoy · Docker · Kubernetes / AWS EKS · Terraform ·
Micrometer / Prometheus / Grafana · OpenTelemetry · JUnit 5 · Testcontainers

Rationale for each choice — and what was deliberately deferred — is in
@docs/tech-stack.md.

## Architecture at a glance

```text
Service Owner → Rule Management → PostgreSQL → Kafka
                                                 │
                        ┌────────────────────────┼──────────┐
                        ▼                        ▼          ▼
                   Deployment               Analytics     Audit
                        │
                        ▼
                      Envoy → Orders / Payments / Inventory
```

Full service breakdown, module layout, and event contracts: @docs/architecture.md

## Build and test commands

```bash
mvn clean verify                                 # full build, tests, quality gates
mvn -pl common test                              # one module's tests
mvn -pl common test -Dtest=PlatformConstantsTest # a single test class
mvn spotless:apply                               # auto-format
docker compose -f infra/docker-compose.yml up -d # local Postgres, Redis, Kafka
docker compose -f infra/docker-compose.yml down  # stop (add -v to wipe data)
```

Commands above are verified working. Module-scoped examples use `common`
because it is the only module so far; substitute `rule-service` once Phase 1
creates it.

Local infrastructure — ports, topics, databases, troubleshooting — is
documented in @infra/README.md. Integration tests do **not** use that stack;
Testcontainers starts its own containers and only needs the Docker daemon
running.

## Local environment

| | |
| --- | --- |
| JDK | **26.0.1** installed; build targets **21** via `<release>21</release>`. Do not install a second JDK. Pin the JDK in CI. |
| `JAVA_HOME` | **unset** — should be set |
| Docker | Desktop 4.76 / Engine 29.5, Compose v5.1. **Daemon must be running** before Testcontainers or `docker compose` work |
| `gh` CLI | **2.97.0 installed** and authenticated |
| `jq` | **not installed** — hooks must not depend on it |
| Repo location | **`C:\dev\ai-traffic-management`** — moved out of OneDrive on 2026-08-16. OneDrive sync held handles on `target/`, making `mvn clean` fail intermittently with "Failed to delete ...\target". That failure mode is now resolved; do not move the repo back under OneDrive. |

## Working agreements

- **Phases are sequential** — do not start a phase before the previous one meets
  its exit criteria. The AI layer is LAST. See @docs/roadmap.md.
- **AWS/EKS/Terraform is deferred** to an optional Phase 5b. Phase 5 targets
  **local Kubernetes**, which teaches every orchestration concept that matters
  without the cost and operational detour. Nothing in phases 0–6 may depend on
  AWS.
- **Enforcement belongs in the data plane.** Bucket4j (Phase 1) protects the
  control plane's *own* API and is a deliberate stepping stone; Envoy takes over
  data-plane enforcement in Phase 3. Simulated business services never contain
  throttling code — see the agreement below.
- **Every change ships with tests.** Kafka consumers additionally need a test
  proving duplicate delivery is safe.
- **Quality gates fail the build**, not warn: Spotless, Checkstyle, SpotBugs,
  JaCoCo.
- **Event schemas are a public API** — additive changes only; no field removal
  or type narrowing without a new topic version.
- **Every remote call needs an explicit timeout.** One without is a bug.
- **Never publish to Kafka inside a database transaction** — use the outbox
  pattern.
- **Simulated business services stay unaware of the platform**, or they stop
  being a realistic test environment.
- **Rules are versioned, never mutated** — `UPDATE` is replaced by `INSERT`, so
  rollback and audit have history to work with. See @docs/adr/0007-immutable-rule-versioning.md.

## Git

- **`main` is protected.** Work on short-lived `feature/<phase>-<slug>` branches,
  merge via squash-merged PRs, tag each completed phase. See @docs/git-workflow.md.
- **Conventional Commits** — `type(scope): subject`, scope = module name.
- A `Stop` hook suggests the next git action; `/git-flow` performs it. The hook
  only ever suggests — it never commits, pushes, or tags on its own.
- A second `Stop` hook (`learning-map.sh`) writes to **`docs/learning-map.md`
  only**, flipping status cells when a phase's exit criteria are all ticked in
  `roadmap.md`. It never touches source, never commits, and never writes the
  prose — that is written by hand at the end of each phase.
- **Never run `git init`, commit, push, or tag unless asked in that turn.**

## Automation

- **`/context-check`** — re-reads CLAUDE.md, the roadmap, architecture, coding
  standards, and ADRs, verifies them against the live git/build state, then
  plans the requested feature. **Run this at the start of any session after the
  context has been cleared, before planning new work.**
- **`/new-service`** — scaffolds a new Maven module (child POM, parent
  registration, package tree, optional Flyway/Kafka/Testcontainers layers).
- **`/git-flow`** — performs git actions: branch, commit, push, PR, tag.
- A `Stop` hook (`.claude/hooks/git-reminder.sh`) suggests the next git action.
  It only ever suggests — it never commits, pushes, or tags on its own.

## Detailed documentation

- @docs/architecture.md — services, flows, module layout, event contracts
- @docs/tech-stack.md — every technology and the problem it solves
- @docs/coding-standards.md — Java/Spring rules, resilience, observability, testing
- @docs/roadmap.md — phases 0–6: what gets built, exit criteria, git per phase
- @docs/learning-map.md — the same plan by concept: what each phase teaches, and
  what it actually taught. Status is hook-maintained; the write-ups are not.
- @docs/git-workflow.md — branching, commits, PRs, tags, versioning
- @docs/adr/ — architecture decision records

Original planning transcript, superseded: `docs/archive/HLD.md`
