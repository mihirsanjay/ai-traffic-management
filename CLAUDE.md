# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in this repository.

## What this is

A small-scale, production-style **traffic management control plane**. A service
owner defines throttling rules for their APIs, those rules are deployed to a
distributed traffic layer (Envoy), and the resulting traffic is observed and
analyzed. An AI agent layer is added last, on top of the working platform.

**Status: pre-code.** Phase 0 has not started — docs only, no build or source yet.

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

> **Not yet runnable.** These are the intended commands for the Phase 0 build.
> They have not been executed against a real build because none exists yet.
> Verify and update this section when Phase 0 lands.

```bash
mvn clean verify                              # full build, tests, quality gates
mvn -pl rule-service test                     # one module's tests
mvn -pl rule-service test -Dtest=RuleServiceTest#createsNewVersion
mvn spotless:apply                            # auto-format
docker compose -f infra/docker-compose.yml up # local Postgres, Redis, Kafka
```

## Local environment

| | |
| --- | --- |
| JDK | **26.0.1** installed; build targets **21** via `<release>21</release>`. Do not install a second JDK. Pin the JDK in CI. |
| `JAVA_HOME` | **unset** — should be set |
| Docker | Installed, but **daemon must be started** before Testcontainers work |
| `gh` CLI | **not installed** — required for PRs; Phase 0 task |
| `jq` | **not installed** — hooks must not depend on it |

## Working agreements

- **Phases are sequential** — do not start a phase before the previous one meets
  its exit criteria. The AI layer is LAST. See @docs/roadmap.md.
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
- **Never run `git init`, commit, push, or tag unless asked in that turn.**

## Detailed documentation

- @docs/architecture.md — services, flows, module layout, event contracts
- @docs/tech-stack.md — every technology and the problem it solves
- @docs/coding-standards.md — Java/Spring rules, resilience, observability, testing
- @docs/roadmap.md — phases 0–6: what gets built, exit criteria, git per phase
- @docs/git-workflow.md — branching, commits, PRs, tags, versioning
- @docs/adr/ — architecture decision records

Original planning transcript, superseded: `docs/archive/HLD.md`
