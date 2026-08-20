# 0011 — Thinned test strategy and a 0.60 coverage floor

**Status:** Accepted · 2026-08-20

## Context

Phase 1 shipped roughly as many lines of test code as production code — 2,047
against 2,036 — and that ratio bought real defects. Two of Phase 1's most
valuable findings came from tests written specifically to falsify a passing
result: the `@TestConfiguration` inheritance bug that made a broken datasource
look green, and the `max-attempts: 1` vacuity check that proved the concurrency
test's writers genuinely collided.

The project's remaining budget is now three coding sessions before cloud work
begins. At the Phase 1 ratio, the remaining scope does not fit. Test effort is
the largest single lever, and pulling it has to be a deliberate, recorded
decision because it **contradicts standards this project has already written
down**:

- `coding-standards.md` requires *"Every API endpoint has at least one
  integration test"* and *"Every bug fix adds the test that would have caught
  it."*
- `learning-map.md` makes *"a concurrency test that passes proves nothing until
  you prove it can fail"* a headline lesson.

Leaving those documents in place while quietly writing fewer tests would make the
codebase look like it drifted from its own standards. It did not drift; the scope
was cut on purpose.

## Decision

**Thin the test suite. Keep every quality gate.**

Spotless, Checkstyle, SpotBugs, and Enforcer remain unchanged and continue to
fail the build rather than warn. CI still runs `mvn clean verify` on every PR and
still blocks merge. What changes is how much test code is written, and the JaCoCo
floor moves from **0.75 to 0.60**.

### Non-negotiable, still written

1. **Every Kafka consumer has a duplicate-delivery test.** At-least-once delivery
   is a property of the transport, not a hypothetical. A consumer that has never
   been tested against a replay is a consumer whose idempotency is an assumption.
2. **The outbox transaction proof.** That no Kafka publish occurs inside a
   database transaction is the entire justification for the outbox pattern
   existing, and it is invisible in code review — it looks correct either way.

### Retained at reduced volume

- One happy-path integration test per capability, rather than one per endpoint.
- Unit tests on genuine business-logic branches, rather than on plumbing.

### Not written — see `docs/deferred.md`

Vacuity checks, concurrency stress tests beyond Phase 1's, fail-open probes
beyond Phase 1's, and DLT-landing tests.

## Why 0.60 rather than 0.70

The JaCoCo rule is **`BUNDLE`-scoped, so it applies per module independently**.
This is the detail that sets the number, and it is easy to miss:

- `common/` becomes a module of event records — mostly compiler-generated
  accessors, `equals`, `hashCode`, and `toString`. JaCoCo counts those generated
  methods. A module of pure records with a serialization test lands near the
  floor no matter how thoroughly the contract is actually tested.
- `deployment-service` is a new module that must clear the floor **on its own**,
  from its first commit, before it has accumulated the test mass `rule-service`
  has.
- The two simulators are ~80 lines each of controller returning canned data.

At 0.75 those modules block the build for reasons unrelated to test quality.
0.60 is the number at which the floor still catches an entirely untested file —
which is its actual job — without failing a module for being small and
record-heavy.

`rule-service` currently measures around 90% and is expected to stay well above
the floor. **The floor is a trip-wire, not a target**, and lowering it does not
license lowering rule-service's actual coverage.

## Related reduction: `Idempotency-Key`

`coding-standards.md` specifies that the caller supplies `Idempotency-Key` and
*"the server stores the result and replays it on retry."* Storing and replaying a
serialized response body needs its own table and its own lifecycle.

**What ships instead:** a unique constraint, so that the same key against the same
rule version returns the existing deployment rather than creating a second one.
This satisfies the property that matters — a retried request does not produce a
duplicate deployment — at roughly a tenth of the cost. The event-driven path is
the primary one in any case; the manual `POST /deployments` endpoint is a
secondary entry point.

## Consequences

- Regressions in untested paths will be found later, and some will be found in
  the cloud rather than in CI. Accepted: for a single-developer personal project,
  the cost of a late-found bug is hours, not an incident.
- The two non-negotiable tests protect the two properties whose failure would be
  **silent** — duplicate processing and a lost event. That is the criterion used
  to decide what stayed: not "how important is this feature" but "would its
  failure be invisible."
- `coding-standards.md` is amended rather than contradicted — its
  integration-test rule now reads "per capability" — so the document and the
  codebase continue to agree.
- The floor is reviewed at the end of Phase 4. If the modules that forced 0.60
  have grown real test mass by then, it goes back up. It is not a permanent
  lowering of the bar.
- **This is a scope decision, not a change of belief about testing.** The Phase 1
  lessons in `learning-map.md` stand as written, and the practices dropped here
  are listed explicitly in `docs/deferred.md` rather than forgotten.
