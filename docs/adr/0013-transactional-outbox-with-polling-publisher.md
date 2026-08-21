# 0013 — Transactional outbox with a polling publisher

**Status:** Accepted · 2026-08-20

## Context

`CLAUDE.md` and ADR 0005 both forbid publishing to Kafka inside a database
transaction, and give the reason: the commit can fail after the publish
succeeds, leaving an event that describes a state change which never happened.
Neither says how publishing should actually work, and the mechanism was never
chosen.

The underlying problem is that a Postgres commit and a Kafka publish cannot be
made atomic. Every design is therefore a choice about which way to fail.

## Alternatives considered

**Publish after commit, via `@TransactionalEventListener(AFTER_COMMIT)`.** The
cheapest option by a wide margin — no table, no poller. Rejected because a
process that dies between the commit and the publish loses the event with no
record that it was ever owed. That is precisely the failure the outbox exists to
survive, so choosing this would be choosing not to solve the problem.

**Kafka transactions with `KafkaTransactionManager`.** Does not actually help: a
Kafka transaction and a JDBC transaction are still two separate commits, and
coordinating them needs XA, which Postgres and Kafka do not jointly support in
any way worth having.

**Change data capture (Debezium).** Tails the Postgres write-ahead log, so there
is no polling latency and no query load. Genuinely the production-grade answer,
and rejected on cost rather than merit: it adds a Kafka Connect cluster to the
local environment and WAL configuration to Postgres, which is a large operational
surface for a project whose event volume is a human occasionally editing a rule.
Recorded in `deferred.md`.

## Decision

**Write the event to an `outbox` table in the same transaction as the state
change, and drain it with a scheduled poller that claims rows using
`SELECT ... FOR UPDATE SKIP LOCKED`.**

The table is deliberately minimal: a nullable `published_at` is the entire state
machine. There is no attempt counter, no error column, and no status enum. The
pattern's load-bearing property is that the event row commits with the state
change; per-row failure bookkeeping is operational polish, and the dominant
failure mode here — the broker being unreachable — is all-or-nothing across every
row rather than something a per-row counter illuminates.

### The ordering problem, which is the part worth writing down

The claim query's locks only exist inside a transaction, and they are what stop
two pollers from publishing the same row. But `coding-standards.md` forbids
holding a transaction open across a remote call, which is exactly what publishing
is. The two constraints cannot both be satisfied by holding one transaction
across the whole operation.

There are two ways to split it, and they fail in opposite directions:

| Order | Failure on a crash mid-operation | Guarantee |
| --- | --- | --- |
| Claim, mark published, commit, then send | The row is marked sent but never was. **The event is lost.** | At-most-once |
| Claim, commit, send, then mark published | The row is re-claimed and re-sent on restart. **The event is duplicated.** | At-least-once |

**The second is chosen**, and the choice is forced rather than balanced.
At-least-once is the guarantee ADR 0005 already declares and that every consumer
must already handle by keying on `eventId` — a duplicate is safe by construction.
A lost event is not recoverable by anything downstream.

`SKIP LOCKED` still does real work in this arrangement: it keeps the common case
disjoint, so two pollers never fight over the same batch. It simply is not the
sole correctness mechanism; consumer idempotency is.

### `SKIP LOCKED` was kept deliberately

A single-instance deployment does not need it — with one poller there is nothing
to skip. It is kept anyway because dropping it would pin `rule-service` to one
replica, and that is the one service in this platform that has no other reason to
be pinned. Envoy's rate-limit buckets are per-process and the deployment service
is a single xDS writer, so both of those are `replicas: 1` regardless; making the
control-plane API single-instance too would mean nothing in the system could be
scaled horizontally at all.

## Consequences

- **Publication is delayed by up to one poll interval.** Acceptable: the
  consumer of these events reconfigures a proxy, and a second of latency between
  a human editing a rule and the proxy learning about it is not meaningful.
- **Duplicate publication is possible and expected.** Every consumer must be
  idempotent. This is not a new requirement — ADR 0005 already imposed it — but
  it is now load-bearing rather than theoretical.
- **The claim must run inside a transaction, and therefore in a separate bean.**
  Spring applies `@Transactional` through a proxy, so calling the claim from the
  scheduled method on `this` would run it with no transaction, releasing the
  locks immediately. It would appear to work while protecting nothing. This is
  the same trap ADR 0008 documents in `RuleVersionAppender`, and the same fix:
  `OutboxBatchClaimer` is a separate bean so the call crosses the proxy.
- **The outbox write must sit outside `RuleService.create`'s existing
  `try/catch`.** That block converts `DataIntegrityViolationException` into a
  409 duplicate-rule response; an outbox failure inside it would be reported to
  the caller as a duplicate rule, which is a confident wrong answer about an
  entirely different table.
- **The write for updates lives in `RuleVersionAppender`, not
  `RuleService.update`.** `update()` is deliberately non-transactional so the
  retry can sit outside a transaction boundary (ADR 0008); a write there would
  run outside any transaction *and* fire once per retry attempt, emitting an
  event for every failed try.
- **The outbox table grows without bound.** The same class of debt as ADR 0007's
  unbounded `rule_versions`, and the same answer: a retention policy is needed
  eventually, not before Phase 5. The partial index means query performance
  tracks the backlog rather than the table size, so the growth is a storage
  concern rather than a latency one.
- **Consumer idempotency needs a pre-check as well as the constraint.** Building
  the consumer revealed that "let the unique constraint fire and catch the
  violation" — the obvious reading of at-least-once safety, and what an earlier
  draft of this ADR implied — does not work on its own. By the time Postgres
  raises `23505` the transaction is marked rollback-only, so catching the
  exception does not save the commit; the listener fails, the record redelivers,
  and it fails the same way forever. The consumer checks its ledger first and
  keeps the constraint as the backstop for the two-consumer race a check cannot
  win. Recorded here because the failure is a redelivery loop rather than a
  duplicate, which points nowhere near the cause.
- **A poison row would be retried forever.** There is no give-up path, and that
  is intentional: an outbox row describes a change that has already committed, so
  abandoning it would mean silently dropping a real event. If a payload the
  broker rejects outright ever appears — over `max.request.size`, say — it is
  handled by hand. The backlog gauge is what makes it visible.
