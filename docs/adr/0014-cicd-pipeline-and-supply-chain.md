# 0014 — CI/CD pipeline and supply-chain security

**Status:** Accepted · 2026-08-24

## Context

Phase 0 built a single CI workflow: `mvn clean verify` on every pull request,
blocking merge on failure. It runs the Java quality gates (Spotless, Checkstyle,
SpotBugs, JaCoCo, Enforcer) and nothing else.

That covers "does the code compile and do the tests pass". It does not cover
anything about what happens *around* the build: whether a dependency has a known
vulnerability, whether a credential was committed, what version this is, how an
artifact reaches a registry, or whether an artifact someone downloads is really
the one this repository produced.

All four services became containerized in the preceding change, which is what
made image publishing possible and forced these decisions rather than leaving
them theoretical.

## Decision

Five workflows, split by trigger and purpose rather than bundled for
convenience:

| Workflow | Trigger | Purpose |
| --- | --- | --- |
| `ci.yml` | PR, push to `main` | Build, test, quality gates |
| `security.yml` | PR, push, **weekly** | Secret scan, dependency CVEs, SBOM |
| `codeql.yml` | PR, push, **weekly** | Dataflow analysis for vulnerabilities |
| `release.yml` | push to `main` | Version, changelog, tag, GitHub Release |
| `publish.yml` | release published | Build, scan, sign, attest, push images |

The pipeline **ends at published images**. It does not deploy, because there is
nothing to deploy to: Kubernetes is Phase 5 and a cloud account is Phase 6. A
future `deploy.yml` consumes what `publish.yml` produces.

### The weekly schedule is not redundant with the push trigger

A scan that only runs when code changes cannot discover a vulnerability
disclosed *after* the last commit. The code is identical, so nothing
re-triggers, and the finding never surfaces. Scheduled scans are the only
mechanism that turns "a CVE was published against a dependency you already
shipped" into a signal rather than silence.

### GHCR over Docker Hub

GitHub Container Registry is free for both public and private images, has no
pull rate limits for authenticated users, and authenticates from Actions with
the automatically-provided `GITHUB_TOKEN` — no registry credential to store or
rotate. Docker Hub's free tier rate-limits pulls, which turns a CI dependency
into an intermittent failure that looks like a network problem.

### release-please over semantic-release

Both derive versions from Conventional Commits, which `docs/git-workflow.md`
already mandates. They differ in *when* a release happens.

semantic-release tags immediately on every qualifying push to `main`.
release-please instead maintains a standing **release pull request** that
accumulates changes and keeps `CHANGELOG.md` current; merging that PR is what
creates the tag and the release.

The indirection is the point. `main` is protected and always releasable; making
every merge also a *release* removes the decision. A release PR keeps the
version bump reviewable, which is the same reason every other change here goes
through a PR.

### Keyless signing over managed keys

Images are signed with cosign using GitHub's OIDC identity rather than a
private key. There is no key to generate, store, rotate, or leak, and the
signature is verifiable against the workflow identity that produced it:

```bash
cosign verify ghcr.io/mihirsanjay/rule-service:latest \
  --certificate-identity-regexp="https://github.com/mihirsanjay/.*" \
  --certificate-oidc-issuer="https://token.actions.githubusercontent.com"
```

A signing key stored in repository secrets would be one more secret whose
compromise forges every future signature. Keyless signing has no such secret.

### Three scanners, deliberately

They overlap far less than the names suggest:

- **SpotBugs** (already in `mvn verify`) finds bug *patterns* in bytecode: a
  null dereference, an always-false comparison.
- **CodeQL** does *dataflow* analysis: whether attacker-controlled input can
  reach a dangerous sink. Injection and path traversal are properties of the
  path between two places, which pattern matching cannot see.
- **Trivy** runs twice against different things — the source tree's resolved
  dependency graph, and separately the *built image*, which is the only way to
  see CVEs in the Alpine base layers and the JRE.

Dropping any one of them loses a class of finding the others do not cover.

### `pull_request_target` is prohibited

No workflow may use it. Unlike `pull_request`, it runs with full access to
repository secrets while checking out the pull request's code — so on a public
repository, anyone can open a PR whose code exfiltrates those secrets. It is a
well-documented attack, and the mitigation is simply never to use the trigger.

`claude.yml` therefore fires on comment events (a collaborator typing
`@claude`), never automatically on pull requests. The consequence is accepted:
AI review is on-demand rather than automatic.

### Secret scanning now, a secrets manager later

Gitleaks scans full git history on every PR, with an allowlist for the
deliberately committed local Postgres credentials. Application configs were
changed to `${VAR:default}` form so credentials can be injected from the
environment without editing files.

A real secrets manager (Vault, Infisical) is **not** added yet. It protects
secrets crossing a trust boundary; everything here is loopback-bound and
throwaway, so it would be ceremony around a password documented as guarding
nothing. It arrives in Phase 6 with the first remote deployment, and the
`${VAR:default}` change is what makes that a configuration change rather than a
refactor.

CI secrets live in GitHub Actions repository secrets — encrypted at rest, never
in the repository, redacted from logs.

## Consequences

- **The version numbering was reconciled.** The parent POM said
  `0.1.0-SNAPSHOT` while tags were at `v0.3.0`. Left alone, release-please's
  first release would have been `0.1.1`, colliding with existing history. All
  seven POMs now say `0.3.0-SNAPSHOT` and the manifest is seeded at `0.3.0`.
- **Security gates fail the build rather than annotating.** A scanner whose
  findings are advisory is a scanner people learn to scroll past.
- **The scanners were verified non-vacuous.** Gitleaks was run against a
  planted credential and confirmed to exit non-zero. This matters more than it
  sounds: the first test key used was AWS's documentation placeholder
  (`AKIAIOSFODNN7EXAMPLE`), which scanners deliberately ignore — so the scan
  passed and *looked* identical to working correctly. A security gate that has
  never failed has not been tested.
- **Dependabot PRs will appear weekly** and must pass the same gates as any
  other change. Grouped by ecosystem, because ungrouped Spring Boot bumps
  produce enough PRs that people stop reading them.
- **Two manual setup steps exist outside the repository**: adding
  `ANTHROPIC_API_KEY` to repository secrets, and installing the CodeRabbit
  GitHub App. Both are recorded in `docs/cicd.md`.
- **Build time increases.** CodeQL adds several minutes to every PR. Accepted:
  the alternative is finding an injection vulnerability in production.
- **The pipeline stops short of deployment**, which is the honest state of the
  project rather than an omission. Phase 6 adds `deploy.yml`.
