# CI/CD Pipeline

What runs, when, why it exists, and how to reproduce it locally.

The decisions behind this design — and the alternatives rejected — are in
[ADR 0014](adr/0014-cicd-pipeline-and-supply-chain.md). This document is the
operational reference.

## The pipeline at a glance

```text
   ┌─ open a pull request ─────────────────────────────────────┐
   │                                                            │
   │   ci.yml         build, test, quality gates                │
   │   security.yml   secrets, dependency CVEs, SBOM            │
   │   codeql.yml     dataflow analysis                         │
   │   CodeRabbit     automated review                          │
   │   @claude        on-demand review (comment to trigger)     │
   │                                                            │
   └───────────────────────── all green ───────────────────────┘
                                 │
                            merge to main
                                 │
                                 ▼
              release.yml  ──▶  opens / updates a RELEASE PR
                                 │
                        (a human merges it)
                                 │
                                 ▼
                     tag + GitHub Release created
                                 │
                                 ▼
              publish.yml  ──▶  build → scan → sign → attest → GHCR
```

Nothing is published from an unmerged branch, and nothing is released without
someone merging the release PR.

## The five workflows

| Workflow | Runs on | What it does |
| --- | --- | --- |
| `ci.yml` | PR, push to `main` | `mvn clean verify`: compile, unit + integration tests, Spotless, Checkstyle, SpotBugs, JaCoCo, Enforcer |
| `security.yml` | PR, push, **Mondays 06:00 UTC** | Gitleaks (full history), Trivy dependency scan, CycloneDX SBOM |
| `codeql.yml` | PR, push, **Wednesdays 06:00 UTC** | GitHub's dataflow analysis for injection/traversal classes of bug |
| `release.yml` | push to `main` | Maintains the release PR: version bump + `CHANGELOG.md` |
| `publish.yml` | GitHub Release published | Builds four images, scans, signs, attests, pushes to GHCR |

### Why some workflows also run on a schedule

This is the part that is easy to dismiss as redundant. A scan triggered by code
changes can only ever tell you about the code as it was when it changed. If a
vulnerability is disclosed next month against a dependency you shipped today,
nothing in the repository has changed, so nothing re-runs, and you never find
out.

The weekly runs exist for exactly that case. They are the difference between
learning about a CVE from your own pipeline and learning about it from someone
else.

## The three scanners, and why all three

They sound redundant and are not. Each finds a class of problem the others
structurally cannot.

**SpotBugs** (already part of `mvn verify`) analyses bytecode for known *bug
patterns*: dereferencing something that can be null, a comparison that is always
false, a stream that is never closed. It matches shapes in a single place in the
code.

**CodeQL** performs *dataflow* analysis. It asks whether a value the attacker
controls can travel from where it enters the program to somewhere dangerous —
a SQL query, a file path, an outbound URL. That is how SQL injection and path
traversal are found, and pattern matching cannot find them, because the bug is
not in either location. The bug is the *path between them*.

**Trivy** looks up *known vulnerabilities* in things you did not write. It runs
twice against different targets:

- In `security.yml`, against the resolved Maven dependency tree — including
  transitive dependencies, which is where vulnerable libraries usually hide,
  since nobody adds them deliberately.
- In `publish.yml`, against the *built container image* — which is the only way
  to see CVEs in the Alpine base layers and the JRE. Those are not visible from
  the source tree at all, because they are not in your source tree.

## Releases and version numbers

Version numbers are derived from commit messages. `docs/git-workflow.md` already
requires [Conventional Commits](https://www.conventionalcommits.org/), and
release-please reads them:

| Commit prefix | Version effect | Example |
| --- | --- | --- |
| `fix:` | patch — `0.3.0` → `0.3.1` | `fix(rule-service): correct version pointer` |
| `feat:` | minor — `0.3.0` → `0.4.0` | `feat(deployment-service): add rollback` |
| `BREAKING CHANGE:` in footer | major — `0.3.0` → `1.0.0` | any type, with the footer |
| `docs:`, `test:`, `chore:` | none | `docs: explain the outbox` |

**Releases are not automatic.** On every merge to `main`, release-please updates
a standing pull request titled something like *"chore(main): release 0.4.0"*.
That PR contains the version bump across all seven POMs and the generated
`CHANGELOG.md` entries. It sits there accumulating changes until someone merges
it — and merging it is what creates the git tag and the GitHub Release, which
in turn triggers image publishing.

If you want to release, merge the release PR. If you do not, leave it; it keeps
updating itself.

## Container images

Four images are published to GitHub Container Registry:

```text
ghcr.io/mihirsanjay/rule-service
ghcr.io/mihirsanjay/deployment-service
ghcr.io/mihirsanjay/orders-service
ghcr.io/mihirsanjay/payments-service
```

Each is tagged several ways for different purposes:

| Tag | Example | Use |
| --- | --- | --- |
| Full version | `0.4.0` | Pin to an exact release |
| Major.minor | `0.4` | Follow patches within a minor version |
| Commit SHA | `sha-a1b2c3…` | Reproducibility — never moves |
| `latest` | `latest` | Convenience only; do not deploy from it |

### Verifying an image really came from this pipeline

Every image is signed and carries build provenance. Signing uses **cosign in
keyless mode**, which means there is no private key anywhere — the signature is
tied to the GitHub Actions workflow identity via OIDC, and verifying it checks
that identity:

```bash
cosign verify ghcr.io/mihirsanjay/rule-service:latest \
  --certificate-identity-regexp="https://github.com/mihirsanjay/.*" \
  --certificate-oidc-issuer="https://token.actions.githubusercontent.com"
```

Build provenance records *how* the image was built — which workflow, which
commit, which runner:

```bash
gh attestation verify oci://ghcr.io/mihirsanjay/rule-service:latest \
  --owner mihirsanjay
```

Together these answer "is this image really the one that repository's pipeline
produced from that commit, or did someone push something else to the registry?"

## Secrets

**Nothing sensitive is committed to this repository, and nothing should be.**

The local Postgres credentials (`traffic`/`traffic`) *are* committed, on
purpose. They guard a throwaway container bound to loopback, and they are
allowlisted in `.gitleaks.toml` by value and path — narrowly, so a genuine
credential appearing in the same files is still caught.

CI secrets live in **GitHub Actions repository secrets**. GitHub encrypts them,
stores them outside the repository, injects them at runtime, and redacts them
from logs. Workflow files reference them by name only:

```yaml
anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
```

Application configuration uses `${VAR:default}` so credentials can be supplied
from the environment without editing any file:

```yaml
username: ${DB_USERNAME:traffic}
password: ${DB_PASSWORD:traffic}
```

The default keeps local development and every existing test working unchanged,
while making the value injectable — which is what a secrets manager will supply
in Phase 6. See `.env.example` for the full list of variables.

### `pull_request_target` is prohibited

**No workflow in this repository may use the `pull_request_target` trigger.**

Normally, when someone opens a pull request from a fork of a public repository,
GitHub runs the workflows *without* giving them access to secrets. This is
deliberate: otherwise a stranger could open a pull request whose only purpose is
to print your API key where they can read it.

`pull_request_target` removes that protection — it runs with full secret access.
Combined with checking out the pull request's code, it hands your secrets to
whoever opened the PR. This is a well-documented attack, not a theoretical one.

This is why `claude.yml` triggers on **comment events** instead. It runs only
when a repository collaborator types `@claude`, so untrusted code never executes
while the secret is present.

If you ever find yourself debugging *"why can't this workflow see the secret?"*,
the answer is not to switch triggers.

## Automated review

Two tools, neither requiring anything to be committed:

**CodeRabbit** reviews every pull request automatically. It is free for public
repositories and authenticates as a GitHub App, so no key of ours is involved.
Its configuration lives in `.coderabbit.yaml` and points it at
`docs/coding-standards.md`, so its review reflects this project's actual rules
rather than generic Java advice.

**Claude** responds on demand. Type `@claude` in a pull request comment — asking
a question, or asking for a review of a specific concern — and `claude.yml`
runs. It requires `ANTHROPIC_API_KEY` in repository secrets.

## Dependency updates

Dependabot opens grouped pull requests weekly for Maven dependencies, GitHub
Actions versions, and Docker base images. They go through the same CI and
security workflows as anything else, which is what makes automating them safe:
an update that breaks the build or introduces a CVE cannot merge.

Grouping matters. Ungrouped, a single Spring Boot release produces dozens of
separate pull requests — enough noise that people stop reading them, which
defeats the purpose.

## Running the checks locally

Everything the pipeline does can be reproduced before pushing:

```bash
# The full build, exactly as ci.yml runs it
mvn clean verify

# Auto-fix formatting rather than waiting for Spotless to fail
mvn spotless:apply

# Secret scan across full history (needs Docker)
docker run --rm -v "$(pwd):/repo" -w /repo zricethezav/gitleaks:latest \
  detect --source /repo --config /repo/.gitleaks.toml --redact -v

# Dependency vulnerability scan
docker run --rm -v "$(pwd):/repo" aquasec/trivy:latest fs --scanners vuln \
  --severity CRITICAL,HIGH /repo

# Build a container image
docker build -f rule-service/Dockerfile -t rule-service:local .
```

CodeQL is impractical to run locally and is left to CI.

### Testing that a security gate actually works

A scanner that has never produced a finding has not been shown to work. When
changing scanner configuration, verify it can still fail — write a realistic
fake credential to a scratch file, confirm the scan exits non-zero, then delete
it.

Use a *realistic random-looking* value. AWS's documentation placeholder
(`AKIAIOSFODNN7EXAMPLE`) is deliberately ignored by every scanner to avoid
false positives from copied documentation, so testing with it produces a clean
scan that looks exactly like a working one. That mistake was made while building
this pipeline, which is why it is written down here.

## One-time setup

These cannot be done from the repository and must be done in the GitHub UI:

1. **Add `ANTHROPIC_API_KEY`** — Settings → Secrets and variables → Actions →
   New repository secret. Required for `claude.yml`.
2. **Install the CodeRabbit GitHub App** — <https://github.com/apps/coderabbitai>.
   Required for `.coderabbit.yaml` to have any effect.
3. **Make the GHCR packages public** (optional) — Settings → Packages, after the
   first publish, if anonymous `docker pull` is wanted.
4. **Add the new workflows as required status checks** — Settings → Branches →
   branch protection for `main`. GitHub only offers checks it has seen run at
   least once, so do this after the first green run.

## What this pipeline does not do

It does not deploy anything. There is no Kubernetes cluster (Phase 5) and no
cloud account (Phase 6) to deploy to, so the pipeline ends at published,
verified images. That is the honest boundary rather than an omission — a future
`deploy.yml` consumes exactly what `publish.yml` produces.

It also does not run a secrets manager, for the reason given above: there is
nothing crossing a trust boundary yet for one to protect.
