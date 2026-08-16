---
name: git-flow
description: Perform a git workflow action for this project - create a feature branch, stage and commit with a Conventional Commits message, push, open a PR, or tag a phase release. Use when the user affirms a git-reminder hook suggestion, or invokes /git-flow directly. Follows docs/git-workflow.md.
---

# Git Flow

Executes the git actions defined in `docs/git-workflow.md`. Typically invoked
when the user affirms a suggestion from the `Stop` hook
(`.claude/hooks/git-reminder.sh`), or directly via `/git-flow`.

**Determine the intent from context.** If the user said "yes" to a hook
suggestion, do that action. If they invoked `/git-flow` with an argument
(`/git-flow commit`), do that. If genuinely ambiguous, run `git status` and ask.

Never chain actions the user did not ask for. "Yes, commit" means commit — not
commit-and-push-and-open-a-PR.

## Before any action

```bash
git status --porcelain && git branch --show-current
```

Know the actual state before changing it.

## Create a feature branch

Naming: `<type>/<phase>-<slug>` — `feature/`, `fix/`, `chore/`, `docs/`,
`refactor/`. Derive the slug from the work in progress; keep it short and
hyphenated.

```bash
git switch main
git pull --ff-only origin main     # skip if no remote yet
git switch -c feature/phase-1-rule-crud
```

Uncommitted changes carry over on checkout — that is the intended path when the
hook catches work started on `main`. If `git switch` refuses due to a conflict,
stop and report it rather than forcing.

## Stage and commit

1. `git status` and `git diff` — read what actually changed.
2. Stage deliberately. Prefer explicit paths over `git add -A`; never stage
   build output, `.env`, or credentials.
3. Write a [Conventional Commits](https://www.conventionalcommits.org/) message
   **from the real diff**, not from what was planned:

```
<type>(<scope>): <subject>

<why this change exists - the diff already shows what>

<footer: BREAKING CHANGE / issue refs>
```

- Scope = module name (`rule-service`, `common`, `infra`), or omit if it spans
  several.
- Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `perf`,
  `build`, `ci`.
- Imperative mood, subject ≤ 72 chars, no trailing period.
- `BREAKING CHANGE:` in the footer for any API or event-contract break.

```bash
git commit -m "$(cat <<'EOF'
feat(rule-service): add version history sub-resource

Deployments need to reference an immutable rule version so rollback has
something to roll back to.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

If a pre-commit hook rejects the commit, fix the cause. Never pass
`--no-verify`.

## Push

```bash
git push -u origin "$(git branch --show-current)"    # first push
git push                                              # subsequent
```

Never `push --force` to a shared branch. If a rebase requires it, use
`--force-with-lease` and only on your own feature branch.

**Never push directly to `main`** — it is protected, and the push will be
rejected. Work goes through a PR.

## Open a pull request

Requires the `gh` CLI — **installed and authenticated** (v2.97.0). If
`gh --version` ever fails, say so and stop rather than improvising.

```bash
gh pr create --title "feat(rule-service): add version history" --body "$(cat <<'EOF'
## What
Adds GET /rules/{id}/versions and GET /rules/{id}/versions/{n}.

## Why
Deployments reference an immutable (ruleId, version) pair so rollback has a
concrete target.

## Testing
- Unit tests for version increment
- Testcontainers integration test for the full lifecycle
- Concurrency test: parallel updates never duplicate a version

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Fill the sections from the real change. Push the branch first if it has no
upstream.

## Tag a phase release

Only on `main`, only after **every** exit criterion for the phase in
`docs/roadmap.md` is ticked. Verify that before tagging — check the boxes are
actually checked, do not assume.

```bash
git switch main && git pull --ff-only
git tag -a v0.1.0 -m "Phase 0: build, quality gates, local infrastructure"
git push origin v0.1.0
```

Phase → tag mapping is in `docs/git-workflow.md`.

## Rules

- **Never** commit, push, or tag without the user asking in this turn.
- **Never** `git init` unprompted.
- **Never** `--no-verify`, or `--force` on a shared branch.
- **Never** commit secrets. If one has already been committed, say so plainly —
  the credential must be rotated, since deleting the file does not remove it
  from history.
- Report what happened, including failures, with the actual command output.
