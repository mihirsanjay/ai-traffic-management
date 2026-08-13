# 0007 — Rules are versioned, never mutated

**Status:** Accepted · 2026-08-12

## Context

A service owner changes the Orders limit from 100 to 500 req/min. The obvious
implementation is one row per rule, updated in place:

```
rules
┌─────────┬──────────┬───────┐
│ rule_id │ endpoint │ limit │
├─────────┼──────────┼───────┤
│ abc     │ /orders  │  500  │   <- was 100, overwritten
└─────────┴──────────┴───────┘
```

The previous value is destroyed. Three requirements elsewhere in the design then
become unimplementable:

1. **Rollback.** Phase 2 requires rolling a deployment back to a prior
   configuration. There is nothing to roll back to.
2. **Knowing what is live.** A deployment record saying "deployed rule `abc`"
   becomes a lie the moment someone edits `abc`. "What configuration is running
   right now?" has no reliable answer.
3. **Audit.** The audit log is specified to record *"User X changed Orders quota
   from 100 → 500."* Producing that requires both values.

## Decision

Rules have stable identity; each change **appends an immutable version row**.
`UPDATE` is replaced by `INSERT`.

```
rules                                  rule_versions
┌─────────┬─────────────────┐          ┌─────────┬─────────┬───────┬────────────┐
│ rule_id │ current_version │          │ rule_id │ version │ limit │ created_at │
├─────────┼─────────────────┤          ├─────────┼─────────┼───────┼────────────┤
│ abc     │        2        │───┐      │ abc     │    1    │  100  │ Aug 01     │
└─────────┴─────────────────┘   └─────>│ abc     │    2    │  500  │ Aug 08     │
                                       └─────────┴─────────┴───────┴────────────┘
                                        PK (rule_id, version)
```

`rules.current_version` answers "what version is this rule on" without scanning
history. Deployments reference an explicit `(rule_id, version)` pair, which
cannot change underneath them.

Version numbers are **per rule**, starting at 1 — not drawn from a global
sequence, which would produce confusing gaps (`1, 7, 23`) for no benefit.

## Consequences

- Rollback is redeploying a prior version. It works because the version still
  exists.
- The composite primary key `(rule_id, version)` makes duplicate versions
  physically impossible. Correctness does not depend on application code being
  right.
- **Concurrent updates need explicit handling.** Two simultaneous writers both
  read `current_version = 2` and both try to insert version 3. Left unaddressed
  this is a primary-key violation or a lost update. Resolution is deferred to
  Phase 1 — pessimistic (`SELECT ... FOR UPDATE`) or optimistic (`@Version` plus
  a conditional update and retry) — and gets its own ADR once decided against a
  real concurrency test.
- Reads cost slightly more: either a join to the current version or a
  denormalized pointer. The pointer is preferred.
- **Storage grows without bound.** A rule edited daily accumulates rows forever.
  A retention policy (keep last N, or archive beyond a time window) is needed
  eventually, though not before Phase 5.
- Deleting a rule becomes a soft delete — a tombstone version — since hard
  deletion would destroy the history this exists to preserve.
- Rule versioning is **storage-level only**. It is unrelated to API versioning
  (`/api/v1`) and to release tags (`v0.2.0`); those version the interface and
  the source code respectively, and all three move independently. The one place
  rule versions surface in the API is `GET /rules/{id}/versions` and the
  deployment endpoint's explicit `version` field.

This is the same pattern as an append-only ledger, or git itself: objects are
immutable and a pointer moves.
