# 0006 — Trunk-based development

**Status:** Accepted · 2026-08-12

## Context

The project should follow industry git practice: feature branches, pull
requests, tags, and versioning. The two mainstream models are Git Flow
(long-running `main` + `develop`, plus release and hotfix branches) and
trunk-based development (one `main`, short-lived branches).

## Decision

Trunk-based development. `main` is always releasable and protected. Feature
branches live hours to days and merge via squash-merged PRs. Releases are tagged
on `main` at phase boundaries.

Git Flow was designed for versioned software with multiple supported releases in
the field — desktop applications, installed products. Its `develop` branch and
release branches exist to stabilise a release while new work continues. A
continuously deployed service has no such need: there is one live version, and
it is whatever `main` points at.

## Consequences

- Short-lived branches mean small, reviewable diffs and rare merge conflicts.
- `main` must stay green, so CI on every PR is mandatory infrastructure, not a
  nicety.
- Squash-merging gives `main` one clean commit per change. Individual work-in-
  progress commits are lost — acceptable, since the PR retains the detail.
- Branch protection blocks direct pushes to `main`, including from the local
  machine. This is deliberate friction.
- Incomplete work that must be merged needs a feature flag rather than a
  long-lived branch.
- Conventional Commits on a linear history makes changelog generation from the
  git log mechanical.
