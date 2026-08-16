---
name: new-service
description: Scaffold a new Maven module for this monorepo - child POM, parent registration, package tree, Spring Boot app class, and optional Flyway/Kafka/Testcontainers layers. Use when adding a new service or module (rule-service, deployment-service, audit-service, a simulator), or when invoked directly as /new-service.
---

# New Service

Creates a new Maven module wired into the monorepo, following the conventions in
`docs/coding-standards.md` and `docs/architecture.md`.

**Why this exists:** a module missing from the parent `<modules>` list fails
*silently* — it simply never builds, and no quality gate ever runs against it.
The failure mode is invisible, so the wiring is worth getting right mechanically.

## Before creating anything

Confirm the module is actually due. Phases are sequential
(`docs/roadmap.md`) — do not scaffold a Phase 3 simulator while Phase 1 is open.

```bash
grep -n "<module>" pom.xml          # what already exists
git branch --show-current           # should be a feature/ branch, not main
```

Ask the user for anything genuinely ambiguous — module name, and which layers it
needs (Postgres/Flyway, Kafka, Redis, REST). Do not guess at persistence or
messaging: an unused Flyway directory is harmless, but a missing one that the
service assumed is a runtime failure.

## 1. Child POM

Copy the shape of `common/pom.xml` — it is the reference. Key rules:

- **No `<version>`** in the child. The parent manages it.
- **No `<groupId>`** in the child. Inherited.
- The `<parent>` block is copied verbatim (`com.mihir.traffic` /
  `ai-traffic-management` / current version).
- Write a real `<description>` explaining what the module is *for*. The existing
  POMs document rationale, not just identity — match that.
- Dependencies come from the parent's `<dependencyManagement>`; declare
  `<groupId>`/`<artifactId>` only, never a version. A version in a child POM
  will trip the Enforcer's `dependencyConvergence` rule.

```xml
<parent>
  <groupId>com.mihir.traffic</groupId>
  <artifactId>ai-traffic-management</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</parent>

<artifactId>rule-service</artifactId>
<name>rule-service</name>
```

## 2. Register in the parent

Add to `pom.xml` `<modules>`. **This is the step that fails silently if missed.**

```xml
<modules>
  <module>common</module>
  <module>rule-service</module>
</modules>
```

## 3. Package and directory tree

Base package is `com.mihir.traffic.<module>` (hyphens dropped:
`rule-service` → `com.mihir.traffic.ruleservice`).

```
<module>/
  pom.xml
  src/main/java/com/mihir/traffic/<pkg>/
    <Name>Application.java          # @SpringBootApplication
    controller/  service/  repository/  domain/  config/
  src/main/resources/
    application.yml
    db/migration/                   # only if it touches Postgres
  src/test/java/com/mihir/traffic/<pkg>/
```

Layering is enforced by review today and should be enforced by ArchUnit later:
controllers never touch repositories directly, `@Transactional` never appears on
a controller.

## 4. Conventions that are easy to miss

From `docs/coding-standards.md` — these are the ones that bite:

- **Constructor injection only.** No `@Autowired` fields.
- **Checkstyle requires Javadoc on public methods**, and runs on test sources
  too. A missing Javadoc fails the build at `validate`, before compilation.
- **Every remote call needs an explicit timeout.** One without is a bug.
- **`/api/v1` prefix** on REST paths; `ProblemDetail` (RFC 7807) for errors.
- **No `Thread.sleep` in tests** — use Awaitility.
- Records over classes for immutable data; never `Optional` as a field or
  parameter.

## 5. If it uses Postgres

- Flyway migrations in `src/main/resources/db/migration/`, named
  `V<n>__<snake_case_description>.sql`.
- **Migrations are immutable once applied** — editing one that has run breaks
  startup on a checksum mismatch. Fix forward with a new migration.
- Per ADR 0007, rules are versioned, never mutated: prefer `INSERT` over
  `UPDATE` for anything version-bearing.
- Add an `AbstractIntegrationTest` with `@Testcontainers` — integration tests
  start their own containers and need only the Docker daemon, not
  `infra/docker-compose.yml`.

## 6. If it consumes Kafka

- **Never publish inside a database transaction** — use the transactional outbox
  (write the event to an outbox table in the same transaction, publish from
  there).
- Every consumer needs a test proving duplicate delivery is safe. This is a
  stated requirement in `CLAUDE.md`, not a nice-to-have.
- Event schemas in `common/` are a public API: additive changes only.

## 7. Verify

```bash
mvn -pl <module> verify
```

Scope to the new module rather than running `clean verify` on the whole repo —
it is faster and the failure output is easier to read. Run the full build before
opening a PR.

If the module does not appear in the reactor output, step 2 was missed.

## 8. Finish

Report what was created, then stop. Do **not** commit — `/git-flow` handles git,
and only when the user asks in that turn.
