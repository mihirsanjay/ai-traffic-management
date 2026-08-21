# Git Workflow

**Trunk-based development.** `main` is always releasable. Work happens on
short-lived branches that merge back within hours or days, never weeks.

This is what most modern service teams actually do, and it is deliberately
lighter than Git Flow — no long-running `develop`, no release branches. Those
exist to manage versioned desktop releases, which this project does not have.

## Branches

`main` is protected: no direct pushes, PR required, CI must pass.

```
main ──●────●────●────●──▶   always green, tagged at phase boundaries
        \  /      \  /
     feature/phase-1-rule-crud
                feature/phase-1-versioning
```

Naming — `<type>/<phase>-<slug>` while phases are running:

| Prefix     | For                                   | Example                            |
| ---------- | ------------------------------------- | ---------------------------------- |
| `feature/` | New capability                        | `feature/phase-2-deployment-service` |
| `fix/`     | Bug fix                               | `fix/outbox-duplicate-publish`     |
| `chore/`   | Build, deps, tooling                  | `chore/bump-spring-boot-3-4`       |
| `docs/`    | Documentation only                    | `docs/adr-locking-strategy`        |
| `refactor/`| Behaviour-preserving restructuring    | `refactor/extract-config-generator`|

Rules:

- **Branch from an up-to-date `main`** — `git switch main && git pull` first.
- **One logical change per branch.** If the PR description needs the word "and",
  it is probably two branches.
- **Rebase to update, never merge `main` in** — keeps history linear and the
  diff honest: `git fetch origin && git rebase origin/main`.
- **Delete after merge.** Stale branches are noise.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/) — the format that
makes automated changelogs and semantic version bumps possible.

```
<type>(<scope>): <subject>

<body: why, not what>

<footer: breaking changes, issue refs>
```

Scope is the module name. Types: `feat`, `fix`, `docs`, `style`, `refactor`,
`test`, `chore`, `perf`, `build`, `ci`.

```
feat(rule-service): add version history sub-resource

Deployments need to reference an immutable rule version so rollback
has something to roll back to. Exposes GET /rules/{id}/versions.

Refs: #14
```

- **Imperative mood** — "add", not "added". Completes the sentence *"this commit
  will…"*.
- **Subject ≤ 72 characters**, no trailing period.
- **The body explains why.** The diff already shows what changed.
- **Commit working increments.** Every commit on `main` should build.
- `BREAKING CHANGE:` in the footer for anything that breaks an API or event
  contract.

## Pull requests

Every change goes through a PR — yes, even solo. The PR is where CI runs, where
the change gets a written rationale, and what makes the history readable later.

- **Keep them small.** Under ~400 lines of diff where possible.
- **Fill in the template** — what changed, why, how it was tested.
- **CI must be green.** Never merge red.
- **Squash-merge**, so `main` gets one clean commit per change. Messy
  work-in-progress commits on the branch are fine; they collapse on merge.
- **Self-review first** — read your own diff before requesting review. Most
  obvious mistakes are caught here.

## Tags and versioning

[Semantic Versioning](https://semver.org/), annotated tags, one per completed
phase:

```
v0.1.0   Phase 0 — foundation
v0.2.0   Phase 1 — control plane core
v0.3.0   Phase 2 — event backbone
v0.4.0   Phase 3 — real data plane
v0.5.0   Phase 4 — core complete
v0.9.0   Phase 5 — containers and local Kubernetes
v1.0.0   Phase 6 — cloud, CI/CD, secrets
```

`0.x` signals pre-production. `v0.9.0` means the platform is complete and
orchestrated but not yet deployed for real; `v1.0.0` lands when it actually runs
in a cloud. Phase 7 (AI layer) is benched and reserves no tag.

```bash
git tag -a v0.1.0 -m "Phase 0: build, quality gates, local infrastructure"
git push origin v0.1.0
```

Tag only on `main`, only after the phase's exit criteria are all ticked.

**Three unrelated version concepts in this project — do not confuse them:**

| Versions what        | Where            | Example                    |
| -------------------- | ---------------- | -------------------------- |
| Source code releases | Git tags         | `v0.2.0`                   |
| The HTTP contract    | URL prefix       | `/api/v1/rules`            |
| Rule data (rows)     | PostgreSQL       | `(rule_id=abc, version=2)` |

They move independently. See @docs/architecture.md for rule versioning.

## Never commit

- Build output — `target/`, `*.class`, `*.jar`
- Secrets — `.env`, credentials, keys, `*.pem`. **If one is committed, rotate it;
  removing the file does not remove it from history.**
- IDE files — `.idea/`, `.vscode/`, `*.iml`
- OS files — `.DS_Store`, `Thumbs.db`
- Local overrides — `application-local.yml`

## Automation

A `Stop` hook (`.claude/hooks/git-reminder.sh`) watches for the moments where a
git action is due — uncommitted work on `main`, a finished unit of work, commits
ahead of the remote, a phase whose criteria are all met — and asks. Answering
affirmatively invokes the `/git-flow` skill, which performs the action.

The hook only ever *suggests*. It never commits, pushes, or tags on its own.
