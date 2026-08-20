# Coding Standards

The bar: code that would pass review on a senior team running production
services. Two principles sit above every specific rule below.

**Correctness under failure is the product.** This is a distributed system.
Networks partition, messages arrive twice, dependencies go slow. Code that only
works on the happy path is unfinished.

**Operability is a feature.** If an on-call engineer cannot tell what the system
is doing at 3am from logs, metrics, and traces alone, the work is not done.

## Java 21

- **Records for immutable data** — DTOs, event payloads, value objects. Not for
  JPA entities (they need a no-arg constructor and mutability).
- **Sealed interfaces for closed hierarchies** — deployment states, rule types,
  domain errors. The compiler enforces exhaustive handling; adding a variant
  produces compile errors at every site that must change, which is exactly what
  should happen.
- **Pattern matching over `instanceof` chains** and over visitor boilerplate.
  Switch expressions over closed types must not have a `default` branch — the
  point is to lose exhaustiveness checking nowhere.
- **`Optional` for return values that may be absent.** Never as a field, never
  as a parameter, never in a collection. A parameter that may be absent is
  either an overload or a nullable annotated argument.
- **`var` when the initializer makes the type obvious** (`var rules = new ArrayList<Rule>()`),
  not when it hides it (`var result = service.process(x)`).
- **Virtual threads** where blocking I/O dominates. They do not make
  `synchronized` blocks safe to hold across I/O — use `ReentrantLock` if a lock
  must span a blocking call.
- **No nulls across public API boundaries.** Return empty collections, not null.
  Annotate nullability where it genuinely exists.

## Spring Boot

- **Constructor injection only.** No field `@Autowired`, ever. Constructor
  injection makes dependencies explicit, supports final fields, and keeps the
  class testable without a container.
- **`@ConfigurationProperties` over scattered `@Value`.** Typed, validated,
  discoverable configuration in one place per concern.
- **Layering is strict.** Controllers handle HTTP concerns and nothing else.
  Business logic lives in services. Persistence lives in repositories.
  A controller that contains an `if` about domain rules is misplaced logic.
- **Transaction boundaries are explicit and live at the service layer.** Never
  `@Transactional` on a controller. Never hold a transaction open across a
  remote call — that is how connection pools die.
- **Never publish to Kafka inside a database transaction.** The commit can fail
  after the publish succeeds. Use the transactional-outbox pattern: write the
  event to an outbox table in the same transaction, publish from there.
- **Profiles for environment differences only**, never for behavioural branching
  that changes business semantics.

## API design

- **Resource-oriented URLs**, plural nouns, no verbs: `/rules`, `/rules/{id}`,
  `/rules/{id}/versions`. Actions that are not CRUD become sub-resources
  (`POST /deployments`), not `/doDeployment`.
- **Version from day one**: `/api/v1/...`. Retrofitting versioning is painful.
- **RFC 7807 `ProblemDetail` for all errors.** One error shape across every
  service, with `type`, `title`, `status`, `detail`, and a correlation ID.
  Never leak stack traces or internal messages to callers.
- **Status codes mean what they mean.** `400` the caller is wrong, `404` absent,
  `409` state conflict, `422` semantically invalid, `429` throttled, `5xx` our
  fault. Never `200` with an error body.
- **Pagination on every collection endpoint** — cursor-based, with an enforced
  maximum page size. An unbounded list endpoint is an outage waiting for data
  growth.
- **Idempotency keys on mutating deployment endpoints.** Caller supplies
  `Idempotency-Key`. Implemented as a uniqueness constraint — the same key
  returns the existing deployment rather than creating a second one — rather than
  by storing and replaying the response body. See
  [ADR 0011](adr/0011-thinned-test-strategy.md).
- **Validate at the edge** with Bean Validation; never trust a client.

## Error handling and resilience

- **Never swallow an exception.** No empty catch blocks. Catching to log and
  continue requires a comment explaining why continuing is correct.
- **Runtime exceptions for programming errors, typed domain errors for expected
  failures.** "Rule not found" is a domain outcome, not an exceptional event.
- **Every remote call has an explicit timeout.** A call without one is a bug —
  the default is usually "wait forever", which turns a slow dependency into a
  total outage.
- **Retries use exponential backoff with jitter, and only for retryable
  failures.** Retrying a `400` is pointless; retrying without jitter
  synchronizes clients into a thundering herd. Cap total attempts and total
  elapsed time.
- **Circuit breakers on every outbound dependency.** Fail fast when a dependency
  is down rather than queueing work that cannot complete.
- **Fail closed or fail open — decide deliberately and document it.** If the
  rule store is unreachable, does traffic flow or stop? Both are defensible;
  an undecided answer is not.

## Concurrency

- **Immutability by default.** Immutable objects are thread-safe for free.
- **No shared mutable state without documented ownership.** If two threads touch
  a field, the class comment says which lock guards it.
- **Prefer `java.util.concurrent` to hand-rolled synchronization.**
- **Never block in a reactive or callback context.**

## Observability

- **Structured JSON logs.** Key-value fields, not interpolated prose — logs are
  queried, not read line by line.
- **Never log secrets, credentials, tokens, or PII.** Rule payloads may carry
  customer identifiers; treat them accordingly.
- **Trace ID propagates everywhere**, including across Kafka — inject it into
  event headers on publish and restore it into the logging context on consume.
  A trace that dies at the queue boundary defeats the purpose.
- **Log levels mean something.** `ERROR` = a human must act. `WARN` = degraded
  but handled. `INFO` = significant state change. `DEBUG` = diagnostics. Errors
  that are handled and expected are not `ERROR`.
- **Metric naming**: `<service>_<subject>_<unit>`, e.g.
  `rule_service_deployments_total`, `envoy_requests_throttled_total`. Keep
  cardinality bounded — never a user ID or rule ID as a label.
- **Every service exposes liveness and readiness.** Readiness reflects genuine
  dependency health; a service that reports ready before its Kafka consumer is
  running will drop traffic on deploy.

## Testing

- **Unit tests are fast and deterministic.** No network, no clock dependence, no
  ordering assumptions. A flaky test is worse than no test — it trains people to
  ignore red builds.
- **Testcontainers for anything touching PostgreSQL, Kafka, or Redis.** H2 and
  embedded brokers behave differently from the real thing in exactly the places
  that matter.
- **No `Thread.sleep` in tests.** Await a condition with a timeout (Awaitility).
  Sleeps are simultaneously slow and flaky.
- **Test naming states the behaviour**:
  `deploymentIsIdempotentWhenSameKeyIsReplayed`, not `testDeploy2`.
- **Assert on behaviour, not implementation.** Tests that assert which methods
  were called break on every refactor and validate nothing.

Required before any change merges:

- Business logic branches have unit tests.
- Every **capability** has at least one happy-path integration test. This was
  "every endpoint" until 2026-08-20; see
  [ADR 0011](adr/0011-thinned-test-strategy.md) for why it was reduced and what
  the reduction bought.
- Every Kafka consumer has a test proving duplicate delivery is safe.
- No Kafka publish happens inside a database transaction, and a test proves it.
- Every bug fix adds the test that would have caught it.

The middle two are **non-negotiable** and survived the reduction deliberately:
both guard failures that are otherwise silent. A consumer that double-processes
and an event that is lost after commit both look exactly like success.

## Enforcement

Standards that rely on memory decay. These run in the Maven build and in CI, and
they fail the build rather than producing warnings:

| Tool           | Enforces                                                    |
| -------------- | ----------------------------------------------------------- |
| **Spotless**   | Formatting (google-java-format). Not a review topic.         |
| **Checkstyle** | Naming, structure, complexity limits, banned constructs.     |
| **SpotBugs**   | Correctness and concurrency bug patterns.                    |
| **JaCoCo**     | Coverage floor on new code.                                  |
| **Enforcer**   | Dependency convergence, banned transitive dependencies.      |

Configured in Phase 0, before there is code to retrofit. Coverage is a floor
that catches untested files, not a target to game — high coverage of weak
assertions proves nothing.

## Comments and documentation

- **Comments explain why, not what.** The code says what. A comment restating it
  is noise that goes stale.
- **Javadoc on public APIs and non-obvious invariants**, not on getters.
- **A comment explaining a workaround names the cause** — the ticket, the
  upstream bug, the constraint. Otherwise nobody dares remove it later.
