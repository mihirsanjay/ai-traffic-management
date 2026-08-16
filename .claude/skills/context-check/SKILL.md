---
name: context-check
description: Re-read this project's authored documentation (CLAUDE.md, docs/roadmap.md, architecture, coding standards, ADRs) and report the current state before planning a feature. Use at the start of a fresh session, after the context has been cleared, when the user asks to "check the context files", "catch up", or "get up to speed", or before planning any new feature or phase of work.
---

# Context Check

Rebuilds working knowledge of this project from its authored docs, then plans
the requested feature against what is *actually* true — not what was assumed.

**Why this exists:** the user clears context between major features. Everything
learned in a prior session is gone, but the documentation persists and is the
real source of truth. Planning without re-reading it produces work that
contradicts decisions already made and recorded.

## 1. Read the docs

Read these in order. **Actually read them — do not skim or assume prior
knowledge.** Later files depend on earlier ones for meaning.

| File | What to extract |
| --- | --- |
| `CLAUDE.md` | Working agreements, tech stack, local environment quirks, build commands |
| `docs/roadmap.md` | **Which phase is current, and which exit-criteria boxes are ticked** |
| `docs/architecture.md` | Services, module layout, event contracts, data flows |
| `docs/coding-standards.md` | Java/Spring rules, testing requirements, resilience |
| `docs/git-workflow.md` | Branching, commit format, PR and tag conventions |
| `docs/adr/*.md` | Decisions already made **and their rationale** |
| `infra/README.md` | Local infrastructure, ports, topics, troubleshooting |

Read every ADR, not just the ones that look relevant. They are short, and they
record decisions that are easy to unknowingly contradict — ADR 0007 (immutable
rule versioning) and ADR 0003 (why `common/` stays narrow) are the ones most
often violated by plausible-looking designs.

Skip `docs/archive/HLD.md` — it is explicitly superseded.

## 2. Check the live state

Docs describe intent; git and the filesystem describe reality. Both matter, and
they drift apart.

```bash
git branch --show-current
git status --short
git log --oneline -5
git fetch origin --dry-run 2>&1 | head -3   # is origin ahead?
grep -n "<module>" pom.xml                  # which modules actually exist
```

**Trust the code over the docs when they disagree, and say so.** Stale
checkboxes in `roadmap.md` are a known failure mode — verify claims like "CI
blocks merge" or "main is protected" against the real system before treating
them as done:

```bash
gh api repos/mihirsanjay/ai-traffic-management/branches/main/protection
```

## 3. Report before planning

Give the user a short, factual orientation — not a summary of everything read:

- **Current phase**, and which exit criteria remain unticked
- **Branch and working-tree state**; whether origin has moved ahead
- **What exists** (modules, services) versus what the phase still requires
- **Any doc/reality contradiction** found in step 2
- **Constraints that bind the upcoming work** — the relevant working agreements
  and ADRs, not all of them

## 4. Then plan the feature

Only after the above. The plan must be grounded in what was just read:

- **Respect phase sequencing.** Do not plan Phase 3 work while Phase 1 is open.
  If the user asks for something out of order, say so and let them decide — it
  is their call, not a refusal.
- **Cite the constraints that shape the design**, with file references. If a
  choice is governed by an ADR, name it.
- **Surface open decisions** the roadmap flags for the phase rather than quietly
  picking one. Phase 1's pessimistic-vs-optimistic locking question is an
  example: it is meant to be decided deliberately and recorded as a new ADR.
- **Include the tests the standards require** — every change ships with tests;
  Kafka consumers need a duplicate-delivery test; every endpoint needs an
  integration test.
- **Name the branch** per `docs/git-workflow.md` (`<type>/<phase>-<slug>`), but
  do not create it. `/git-flow` does that, when asked.

## Rules

- **Read before planning, every time.** The cost of re-reading is small; the
  cost of contradicting a recorded decision is a rewrite.
- **Do not modify anything during a context check.** This is a read-and-report
  operation. Planning is not implementing.
- **Do not commit, branch, or push.** That is `/git-flow`, on explicit request.
- If a doc contradicts the code, report the contradiction rather than silently
  picking one. That mismatch is usually the most valuable thing found.
