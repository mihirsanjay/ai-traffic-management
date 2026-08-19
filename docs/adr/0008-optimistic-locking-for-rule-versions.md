# 0008 — Optimistic locking for rule version increment

**Status:** Accepted · 2026-08-16

## Context

ADR 0007 made rules immutable and versioned: a change appends a row to
`rule_versions` rather than updating one. It also left a problem explicitly
open, because resolving it needed a real concurrency test rather than an
argument.

Updating a rule is a read-then-write:

```
1. read  rules.current_version          -> 2
2. insert rule_versions (rule_id, 3, ...)
3. update rules.current_version = 3
```

Two writers running this concurrently both read `2`, both compute `3`, and both
attempt to insert `(rule_id, 3)`. The composite primary key stops the database
from being corrupted — but unhandled, one writer gets a primary-key violation
surfaced as a 500, or its change is silently lost. Phase 1's exit criteria
require that parallel updates never duplicate a version, so this needs a
deliberate answer.

Two standard options:

**Pessimistic** — `SELECT ... FOR UPDATE` on the `rules` row at the start of the
transaction. The second writer blocks until the first commits, then reads
`current_version = 3` and correctly writes 4.

**Optimistic** — no lock. A `lock_version` column on `rules` is included in the
update's `WHERE` clause. If another writer got there first, zero rows match, and
the whole read-compute-insert is retried.

## Decision

**Optimistic locking**, via Hibernate `@Version` on `Rule.lockVersion`, with a
bounded retry using exponential backoff and jitter.

Contention on a single rule is genuinely rare here. A throttling rule is edited
by a human service owner, occasionally; two edits landing in the same
milliseconds on the *same* rule is an unusual event, not the steady state.
Optimistic locking is the right trade when conflict is the exception: it holds
no locks, so the common uncontended path costs nothing, and writers to
*different* rules never interact at all.

Pessimistic locking would also be correct, and is the better choice under heavy
same-row contention. It was rejected because it makes every writer pay a lock
acquisition to protect against a rare case, and a row lock held across a
transaction is a failure mode this project's own standards warn about — the
mechanism by which connection pools die.

The database remains the actual guarantee. The composite primary key
`(rule_id, version)` makes a duplicate version physically impossible regardless
of which locking strategy the application uses; the strategy only determines
whether a concurrent writer gets a clean retry or an ugly error.

## Consequences

- `rules.lock_version` exists from the first migration
  (`V1__create_rules_and_rule_versions.sql`), even though the CRUD branch does
  not yet increment versions. Flyway migrations are immutable once applied, so
  adding the column later would mean a second migration — cheap to include now,
  awkward to retrofit.
- **Retry logic must be bounded and must jitter.** Unbounded retry turns
  contention into an outage, and un-jittered retry synchronizes competing
  writers into a thundering herd. Cap total attempts and total elapsed time.
- A caller can observe a retry as latency, never as an error, until the retry
  budget is exhausted — at which point the honest answer is `409`, not a
  masked success.
- **Under pathological same-rule contention, a writer can starve.** Accepted:
  the workload does not have that shape. If it ever does, the migration path is
  to switch that one transaction to `SELECT ... FOR UPDATE`, which this schema
  already supports without change.
- The concurrency test proving parallel updates never duplicate a version
  lives in `RuleVersionConcurrencyIntegrationTest`, alongside the
  version-increment logic it validates. **This ADR is now proven rather than
  merely decided:** eight latch-released writers contend for one rule against a
  real PostgreSQL, and no version number is ever duplicated. The test was
  checked for vacuity by setting `max-attempts: 1`, under which seven of the
  eight writers lose the race - confirming the writers genuinely collide and
  that the bounded retry is what turns those collisions into successes.
- **The retried step must live in a separate bean.** Spring applies
  `@Transactional` through a proxy, so a self-invocation does not start a
  transaction at all. Putting the append on a method of `RuleService` that
  `update()` calls on `this` makes the annotation inert - the version insert
  and the pointer move then commit as two independent statements, and a failure
  between them orphans the pointer. This was written that way first and caught
  only by probing for an active transaction; the append now lives in
  `RuleVersionAppender` with `REQUIRES_NEW`, and
  `RuleVersionAppenderIntegrationTest` fails if that structure is undone.
- `@Version` makes Hibernate manage the counter, so nothing in application code
  increments `lock_version` by hand.
