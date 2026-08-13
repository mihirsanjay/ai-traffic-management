## What

<!-- What changed. One or two sentences. -->

## Why

<!-- The reason this change exists. The diff shows what; this explains why. -->

## Testing

<!-- How this was verified. Name the tests, not just "tested". -->

- [ ] Unit tests added or updated
- [ ] Integration tests (Testcontainers) where infrastructure is involved
- [ ] `mvn clean verify` passes locally

## Phase

<!-- Which roadmap phase, and which deliverable or exit criterion this advances. -->

## Checklist

- [ ] Follows `docs/coding-standards.md`
- [ ] Conventional Commits format on all commits
- [ ] No secrets, credentials, or `.env` files committed
- [ ] Every remote call has an explicit timeout
- [ ] Kafka consumers are idempotent (duplicate delivery tested)
- [ ] No Kafka publish inside a database transaction
- [ ] Event schema changes are additive only
- [ ] An ADR was added if this makes a notable architectural decision
