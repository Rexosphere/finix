# Contributing to FINIX

## Setup

```bash
git clone https://github.com/Rexosphere/finix.git && cd finix
make hooks                 # install the pre-commit hook (one command, once)
./scripts/gen-secrets.sh   # generate this machine's credentials (never committed)
make demo                  # build, start, seed, print URLs
```

Needs Docker and JDK 21. `make demo-pull` skips the JDK by running published images.

## The loop

```bash
make verify                          # the same gate CI runs for JVM code
make rebuild SERVICE=ledger-service  # rebuild and recreate one service
bash tests/e2e/smoke.sh              # money path still works
```

Non-JVM services have their own toolchains, and CI runs each of them:

```bash
cd services/payment-hub          && gofmt -l . && go vet ./... && go test ./...
cd services/risk-ai-service      && ruff check . && python -m pytest -q
cd services/notification-service && npm ci && npm test
```

## Rules that are enforced, not suggested

| Rule | Enforced by |
|---|---|
| No credential in a tracked file | `gitleaks` job, plus a compose-specific check in CI |
| JVM coverage ≥80% domain / ≥70% overall | `jacocoDomainCoverageVerification` in `make verify` |
| Architecture boundaries (hexagonal) | ArchUnit tests in `make verify` |
| Static analysis | detekt (JVM), ruff (Python), `go vet` + gofmt, CodeQL |
| Conventional commit messages | `commit-lint` job on pull requests |
| Dependency and image CVEs | Trivy (source and published images), Dependabot |

## Conventions

**Commits** follow [Conventional Commits](https://www.conventionalcommits.org/):
`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`, `ci:`, `perf:`, `build:`, `revert:`.
The type is what generates release notes, so `fix: correct hold release on ledger timeout` is worth
more than `fix: bug`.

**Decisions** that someone could reasonably challenge go in `docs/adr/` in MADR format. An ADR is
immutable once merged — a changed decision gets a new ADR that supersedes the old one.

**Comments** explain *why*. The code already says what it does; the comment exists for the reader
who is about to "simplify" something load-bearing.

## Secrets

Nothing secret is ever committed. `scripts/gen-secrets.sh` generates one random value per credential
into `infra/compose/secrets/` (gitignored) and compose mounts them as Docker secrets — see
[ADR-0006](docs/adr/0006-generated-docker-secrets-with-vault-as-runtime-source.md). If you need a
new credential, add it to that script and reference the file; never a literal, never an env default.

The pre-commit hook and the CI secret scan both look for leaks, but they are a safety net, not the
control.

## Releases and deployment

Merging to `master` starts: **CI → Publish images → Deploy**. Each step runs only if the previous
one passed, and production runs the exact images CI verified, pinned to the commit SHA
([ADR-0007](docs/adr/0007-deploy-published-images-not-server-builds.md)).

A bad release is undone with `infra/deploy/rollback.sh` — the previous images are still in GHCR. A
deploy that fails its own health check rolls itself back.
