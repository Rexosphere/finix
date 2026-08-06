# Evidence map

One section per assessed practice: what exists, where it lives, and a command you can run to check
it rather than take our word for it. Where something is deliberately not done, it says so — the
gaps are part of the evidence.

Fastest end-to-end check of the whole thing:

```bash
make verify                 # JVM gate: compile, unit, ArchUnit, coverage, detekt
make demo                   # secrets generated, stack up, seeded, URLs printed
bash tests/e2e/smoke.sh     # the money path actually works
```

---

## 1. Service deployment & environment consistency

**Production runs the artifact CI verified.** `infra/deploy/deploy.sh` checks out the exact commit,
pulls `ghcr.io/rexosphere/finix/<service>:sha-<short>` and starts with `--no-build`. Nothing is
compiled on the server. Before this, the server git-pulled `master`, ran nine Gradle `bootJar`
tasks and `sed`-ed tracked HTML in place — production ran an artifact no CI job had ever seen.

| What | Where |
|---|---|
| Deploy script (tracked, not buried in cloud-init) | `infra/deploy/deploy.sh` |
| Rollback | `infra/deploy/rollback.sh` |
| Production overlay (was untracked on the server) | `infra/compose/docker-compose.prod.yml` |
| Per-environment URLs, served at runtime | `apps/*/nginx.conf` → `/env.js`, `infra/ansible/roles/finix/templates/finix.env.j2` |
| Decision record | [ADR-0007](adr/0007-deploy-published-images-not-server-builds.md) |

```bash
# Same commit → same compose config → same images, in every environment:
docker compose -f infra/compose/docker-compose.yml -f infra/compose/docker-compose.prod.yml \
  --profile full config --quiet && echo "prod config valid"

# On the server: what is running is a commit, not a moving tag
docker compose ps --format '{{.Service}}\t{{.Image}}'
cat /opt/finix/releases.log
```

**Local and production differ only by overlay**, not by a separate file: one
`docker-compose.yml` for structure, `docker-compose.prod.yml` for public URLs,
`docker-compose.vault.yml` for Vault-backed credentials, `docker-compose.scale.yml` for replicas.

## 2. Build & release automation

**CI → Publish images → Deploy**, chained so no link can run ahead of the one before it.
Previously CI and Publish raced on the same push, and Deploy waited on CI while ignoring the images
entirely.

| Stage | File | Gate |
|---|---|---|
| CI | `.github/workflows/ci.yml` | JVM verify, Go, Python, Node, compose config, Ansible syntax, secret scan, CVEs, CodeQL, commit lint |
| Publish | `.github/workflows/publish-images.yml` | `workflow_run` on **successful** CI; tags `sha-<short>`; SBOM + provenance; Trivy image scan |
| Deploy | `.github/workflows/deploy.yml` | `workflow_run` on **successful** publish; health-gated; auto-rollback |
| Nightly chaos | `.github/workflows/nightly-chaos.yml` | saga compensation against a real stack |

```bash
gh run list --workflow=ci.yml --limit 5
gh run list --workflow="Publish images" --limit 5
gh workflow run deploy.yml -f sha=<full-sha>      # deploy a specific commit
```

Actions are pinned to commit SHAs, not tags, and the Gradle wrapper jar is checksum-verified before
it runs (`gradle/actions/wrapper-validation`). Dependabot (`.github/dependabot.yml`) keeps the pins
moving across six ecosystems.

## 3. Automated infrastructure & configuration management

**The host is described, not remembered.** `infra/ansible/` replaces a 300-line cloud-init script
that ran once at first boot and whose outputs — the Caddyfile, the compose override, `monitoring.env`,
`deploy.sh` — existed nowhere but on that machine.

| Role | Owns |
|---|---|
| `base` | packages, timezone, swap, unattended-upgrades, sshd hardening |
| `docker` | engine, `daemon.json` (log rotation, live-restore) |
| `firewall` | ufw + ufw-docker, so Docker's iptables rules cannot bypass default-deny |
| `caddy` | TLS and reverse proxy, hostnames from inventory variables |
| `finix` | app checkout, generated secrets, `finix.env`, nightly backup timer |

```bash
make provision-check     # --check --diff: converges nothing, shows the drift
make provision           # apply
cd infra/ansible && ansible-playbook --syntax-check -i inventory/production.yml site.yml
```

Uses `ansible.builtin` only — no collections — so a bare host needs `ansible-core` and nothing else.
Cloud-init is now 40 lines: install git and Ansible, then `ansible-playbook`.

## 4. Scalability, availability & reliability

| Control | Where | Was |
|---|---|---|
| Health checks on all 12 services | each `services/*/Dockerfile` (`HEALTHCHECK`) | only the 7 infra containers had any |
| Startup ordering on *healthy*, not *started* | `depends_on` in `infra/compose/docker-compose.yml` | orchestrator could route at a service with no datasource |
| Memory/CPU limits per container | `mem_limit`/`cpus` in compose | none — one leak could take the host down |
| Nightly Postgres backup, 7-day retention | `infra/ansible/roles/finix` → `finix-backup.timer` | no backups at all |
| Restore path | `scripts/restore-db.sh`, Drill D in `runbooks/dr-failover.md` | — |
| Release rollback | `infra/deploy/rollback.sh`, Drill E | rebuild from git |
| Horizontal scaling | `infra/compose/docker-compose.scale.yml`, `make scale` | — |
| Chaos, continuously | `.github/workflows/nightly-chaos.yml` | script existed, ran by hand |

```bash
make scale SERVICE=payment-hub N=3     # nginx round-robins; upstreams resolve per request
docker compose -f infra/compose/docker-compose.yml --profile core ps   # health column
make backup && make restore            # take a dump, restore it (interactive)
```

**Honest limits.** One host, so a failure of that host is still total: no multi-AZ, no Postgres
replica, and the nightly dumps live on the same disk as the database they protect. The stateful
services (Postgres, Redis, Redpanda, the JVM services owning a database) do not scale horizontally
here. `docs/FIDELITY-MATRIX.md` records what is deferred to Phase 3.

## 5. Operational visibility & system health

All three pillars, and they are joined to each other.

| Signal | Stack | Notable |
|---|---|---|
| Metrics | Prometheus + node/cadvisor/postgres/redis exporters | `infra/monitoring/prometheus/` |
| Logs | Loki + Alloy (Docker socket discovery) | JSON logs already carry `traceId` |
| Traces | **Tempo** (new) | exporter and log correlation were already in the code; nothing consumed them |
| Alert routing | **Alertmanager** (new) | rules existed and fired into nothing |
| External probes | **Blackbox exporter** (new) | probes the public URLs through Caddy and TLS |

```bash
make monitoring                       # Grafana on :3002, password printed
FINIX_TRACE_SAMPLE=1.0 docker compose -f infra/compose/docker-compose.yml --profile core up -d
# then: transfer money, open Grafana → Explore → Loki → click TraceID on a log line
```

In Grafana a log line links to its trace (Loki derived field → Tempo), a span links back to that
service's logs and metrics, and a latency exemplar links to the trace that caused it. New alerts:
`PublicEndpointDown`, `PublicEndpointTlsExpiringSoon`, `ContainerNearMemoryLimit`.

**Not done:** no paging destination is configured — this demo has no on-call rotation, and a webhook
pointing at a dead URL would be worse than an honest no-op. `alertmanager.yml` says exactly where
the Slack block goes.

## 6. Security practices & protection of sensitive data

**No credential exists in this repository.** Every password is generated per machine into
`infra/compose/secrets/` (gitignored) and mounted as a Docker secret — see
[ADR-0006](adr/0006-generated-docker-secrets-with-vault-as-runtime-source.md). Before this,
`docker-compose.yml` held every Postgres password, `KC_BOOTSTRAP_ADMIN_PASSWORD: admin` and Vault's
root token — and that file is what production ran.

```bash
# The check CI runs on every push:
grep -nE '^\s+[A-Z_]*(PASSWORD|TOKEN|SECRET):\s*\S' infra/compose/docker-compose.yml \
  | grep -vE '_FILE:|__FILE:|\$\{' || echo "no literal credentials"

./scripts/gen-secrets.sh --show     # console logins for this machine
make vault-demo                     # identity-service + vault-service read from Vault via AppRole

# Existing deployment created under the old literal passwords: align the roles
# with the generated files without dropping data (also the rotation command).
./scripts/rotate-db-passwords.sh
```

| Control | Where |
|---|---|
| Secret scanning, full history | `secret-scan` job (gitleaks) |
| Dependency CVEs across 4 ecosystems | `dependency-scan` job (Trivy fs) + `dependency-review` on PRs |
| Image CVEs on what actually ships | Trivy image scan in `publish-images.yml` |
| SBOM + build provenance | `provenance: mode=max`, `sbom: true` — previously disabled |
| Static analysis | detekt (JVM) + CodeQL (Go, Python, JS) |
| Non-root containers | every `services/*/Dockerfile` (uid 10001) |
| No capabilities, no privilege escalation | `x-hardening` anchor in compose |
| Base images pinned by digest | every `services/*/Dockerfile` |
| Least-privilege Vault AppRoles | `infra/compose/vault-bootstrap.sh` |
| Public exposure limited to 4 doors | `infra/ansible/roles/firewall`, `roles/caddy` |
| Branch protection | `scripts/setup-branch-protection.sh` (a repo *setting* — run it to apply) |

**Stated plainly:** Vault runs in dev mode (in-memory, unsealed at boot); the demo persona password
in `infra/keycloak/finix-realm.json` is intentionally public; the AppRole `secret-id` handed to a
service over a volume is this design's weakest link. All three are recorded in ADR-0006 rather than
hidden.

## 7. Engineering best practices

| Practice | Where |
|---|---|
| One command per intent | `make help` |
| Architecture decisions, immutable, MADR | `docs/adr/` (7 records) |
| Runbooks with drills, not prose | `docs/runbooks/` (DR now has restore and rollback drills) |
| Test strategy and fidelity honesty | `docs/qa/TEST-STRATEGY.md`, `docs/FIDELITY-MATRIX.md` |
| Coverage floors enforced in the build | `services/build-logic/.../finix.kotlin-base.gradle.kts` (80% domain / 70% overall) |
| Architecture tests | ArchUnit, in `make verify` |
| Tests for **every** language, run by CI | Go, Python and Node suites existed but no workflow ran them |
| Conventional commits | `commit-lint` job |
| Review routing | `.github/CODEOWNERS` |
| PR and issue templates | `.github/pull_request_template.md`, `.github/ISSUE_TEMPLATE/` |
| Pre-commit hook (fast, staged files only) | `.githooks/pre-commit`, `make hooks` |
| Contributor guide | `CONTRIBUTING.md` |

```bash
make help          # every entry point
make hooks         # install the hook
make verify        # the gate
```

---

## What is not covered

Being explicit about this is part of the practice:

- **No staging environment.** Safety comes from the health gate and automatic rollback, not from a
  second stack. Adding one is a third compose project and a promotion job.
- **Single host.** Availability is bounded by that host; the backups sit on its disk.
- **Vault is dev-mode.** Production needs Raft/file storage, a real unseal ceremony, and service
  identity from platform attestation instead of a bootstrap container handing out `secret-id`s.
- **No paging.** Alerts group, inhibit and display; nobody is woken up.
- **Blackbox targets are hardcoded** to the deployed hostnames, so they read as down on a laptop.
