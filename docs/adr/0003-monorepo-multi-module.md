# 0003 — Monorepo with a single parent build

**Status:** Accepted · 2026-08-12

## Context

The platform comprises several independently deployable services plus a set of
simulated business services. These could live in separate repositories (as they
typically would when owned by separate teams), or in one repository.

## Decision

One repository, one parent POM, one module per service, plus a `common/` module
for genuinely shared code.

Separate repositories model separate team ownership — which does not exist here.
For a single developer, that structure delivers only overhead: cross-repository
version coordination, duplicated build configuration, and changes that must land
atomically split across several PRs.

## Consequences

- An atomic change spanning an event schema and both its producer and consumer
  is a single commit and a single PR.
- Build configuration and quality gates are declared once in the parent POM and
  inherited.
- `mvn -pl <module>` targets a single module when the whole build is unnecessary.
- **`common/` is a standing risk.** Shared modules attract unrelated code and
  quietly couple services that should be independent. It holds event schemas and
  shared error types only. Anything else belongs in the service that uses it.
- Services remain independently deployable: each produces its own container
  image. The shared build does not imply a shared deployment.
- If the project ever needed genuine team separation, splitting the repo later is
  mechanical.
