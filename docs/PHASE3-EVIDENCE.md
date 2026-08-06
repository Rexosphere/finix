# FINIX Phase 3 Evidence

One page per rubric category: what exists, where it lives, how to check it, what to show live, and what we are **not** claiming.

**Reading rules used throughout this document**

| Label | Meaning |
|---|---|
| **IMPLEMENTED IN CURRENT BRANCH** | Committed on this branch and verifiable from the paths given |
| **IN WORKING TREE (UNCOMMITTED)** | The files exist here but are not committed, therefore **not deployed** — production deploys `master` |
| **PENDING MERGE** | Work expected on another branch; not present here and not claimed |
| **CONFIGURABLE BUT NOT ENFORCED** | Code path exists and is switchable, but the running default does not apply it |
| **DEMO-SCALE** | Real algorithm, deliberately scoped to a laptop/compose demo |

This branch is `comp/judge-tests`. `.github/workflows/deploy.yml` deploys only from `master`/`main`, so **nothing uncommitted or unmerged here is running in production.**

The existing [`docs/FIDELITY-MATRIX.md`](FIDELITY-MATRIX.md) is the longer claim-by-claim honesty table and remains authoritative where the two overlap.

---

## Production

| Surface | URL |
|---|---|
| Live customer app | https://roboti.qzz.io |
| Live admin console | https://admin.roboti.qzz.io |
| Identity provider | https://auth.roboti.qzz.io |

Reachability of all three is asserted by CI on every deploy — `.github/workflows/deploy.yml` "Verify public endpoints" fails the job unless the customer and admin hosts return `200` and the auth host returns `302`. Hostnames are provisioned in [`infra/cloud-init/user-data.yaml`](../infra/cloud-init/user-data.yaml) (Caddy, automatic TLS).

**Read-only smoke verification: 27 PASS / 0 FAIL — externally recorded.**
This figure is *not* reproducible from a committed repository artefact and should be presented as an externally recorded verification result, not as repo evidence. The read-only smoke harness that produces this shape of output exists in this working tree (`tests/e2e/finix-smoke.sh`, **IN WORKING TREE (UNCOMMITTED)**) but no production run log is committed anywhere in the repo. The committed, reproducible health check is the older [`tests/e2e/smoke.sh`](../tests/e2e/smoke.sh), which targets `localhost` ports rather than the public host.

No credentials appear in this document by design.

---

## 1. Service Deployment & Environment Consistency — 15%

**What FINIX implements.** Twelve independently deployable services (9 JVM/Kotlin, 1 Python, 1 Go, 1 Node) plus two static nginx apps, one Dockerfile each, one compose topology shared by laptop and server, with the server differing only by a tracked override file.

**Where it lives.**

| Evidence | Path |
|---|---|
| 12 service Dockerfiles | `services/*/Dockerfile` |
| Compose topology (profiles `core`, `security`, `monitoring`, `full`) | [`infra/compose/docker-compose.yml`](../infra/compose/docker-compose.yml) |
| Per-database bootstrap | [`infra/compose/init-databases.sql`](../infra/compose/init-databases.sql) |
| Production-only differences | [`infra/cloud-init/user-data.yaml`](../infra/cloud-init/user-data.yaml) → `/opt/finix/docker-compose.override.yml` |
| Image naming / tags | `.github/workflows/publish-images.yml` → `ghcr.io/<owner>/<repo>/<service>` with `latest`, `sha-<short>`, semver tags |
| Health endpoints | `/actuator/health` on the 9 JVM services, `/health` on risk-ai, payment-hub, notification |
| Compose `healthcheck:` blocks | 7 containers only — `postgres`, `redis`, `redpanda`, `keycloak`, `risk-ai-service`, `prometheus`, `loki` (see limitation below) |
| Reverse proxies | [`apps/web/nginx.conf`](../apps/web/nginx.conf), [`apps/admin/nginx.conf`](../apps/admin/nginx.conf) |

**How to verify.**
```bash
docker compose -f infra/compose/docker-compose.yml --profile core config --quiet   # same check CI runs
bash tests/e2e/smoke.sh                                                            # health across all services
```

**Show live.** `docker compose ps` on the server (all healthy), then the same compose file open locally — one topology, two environments.

**Honest limitations.**
- Environment consistency is *near*, not exact: production adds a Caddy TLS layer and an override file that the laptop does not have, and runs `--profile full` versus `core`.
- `SPRING_PROFILES_ACTIVE` is set nowhere, so every service boots on Spring's `default` profile in both environments.
- **The nine JVM services have no compose `healthcheck:`.** They expose `/actuator/health`, and `deploy.sh` and `tests/e2e/` poll it, but Docker itself reports them `running`, never `healthy` — so `docker compose ps` cannot be presented as a health verdict for those containers. Only the 7 containers listed above carry a probe Docker evaluates.
- ADR-0003 documents logical database-per-service inside one Postgres instance — not separate database servers.

---

## 2. Build & Release Automation — 20%

**Pipeline as it actually runs.**

```
push to master
   ├─► CI  (.github/workflows/ci.yml)
   │      ./gradlew verify  →  compile + unit + ArchUnit + coverage + detekt
   │      scripts/check-lite-budget.sh   (50 KB / zero-JS budget)
   │      detekt SARIF → GitHub code scanning
   │      docker compose config --quiet  (compose validity)
   │
   ├─► Publish images (.github/workflows/publish-images.yml)
   │      bootJars → 12-service matrix → buildx → GHCR
   │      tags: latest, sha-<short>, v*, semver
   │
   └─► Deploy (.github/workflows/deploy.yml)   [only after CI success, only on master/main]
          SSH → /opt/finix/deploy.sh on the server
          git reset --hard @{u} → ./gradlew bootJar → docker compose up -d --build
          wait for health → seed → verify public endpoints from GitHub's runner
```

**Where it lives.** `.github/workflows/ci.yml` (63 lines), `.github/workflows/publish-images.yml` (212 lines), `.github/workflows/deploy.yml` (75 lines). The deployment script is tracked as a here-doc inside [`infra/cloud-init/user-data.yaml`](../infra/cloud-init/user-data.yaml) at `- path: /opt/finix/deploy.sh`.

**Deployment gating that genuinely exists.** Deploy triggers on `workflow_run` of CI with `conclusion == 'success'`, uses `concurrency: deploy-production` with `cancel-in-progress: false`, targets a GitHub `environment: production`, pins `StrictHostKeyChecking=yes`, and deletes the SSH key in an `if: always()` step.

**Honest limitation — this is the weakest link and we will say so first.**

> **Production rebuilds from source; it does not deploy the CI-built artefact.**
> `publish-images.yml` builds and pushes twelve digest-addressable images to GHCR, and `deploy.sh` ignores them: it runs `git reset --hard @{u}`, `./gradlew bootJar`, then `docker compose up -d --build`. The bits serving traffic are therefore built on the production host, not the bits CI tested. Compose *does* reference `${FINIX_IMAGE_PREFIX}/…:${FINIX_IMAGE_TAG}`, so the fix is to pull the CI tag instead of passing `--build` — a change to compose/deploy scripting that is **not** made on this branch.

Further limitations:
- **No rollback path.** `deploy.sh` resets to branch tip with no tag pinning and no retention of the previous image, so rollback today means "revert the commit and redeploy". Untested.
- **No deployed-SHA record.** `buildInfo()` is enabled in `finix.spring-service.gradle.kts`, so `/actuator/info` carries version and build time, but no git plugin injects the commit SHA. Which commit is live cannot be read from the running system.
- CI does not build images and image publishing is not gated on CI passing — the two run independently off the same push.

**Show live.** GitHub Actions run list (CI green → Deploy green → "Verify public endpoints" step output), then GHCR package tags.

---

## 3. Automated Infrastructure & Configuration Management — 15%

**Automated and reproducible.**

| Piece | Path | Notes |
|---|---|---|
| Whole-server provisioning | [`infra/cloud-init/user-data.yaml`](../infra/cloud-init/user-data.yaml) | Packages, swap, Docker daemon config, Caddy (auto-TLS for 4 hostnames), compose override, deploy script, first boot runs the deploy |
| Service topology | [`infra/compose/docker-compose.yml`](../infra/compose/docker-compose.yml) | Four profiles from one file |
| Identity realm as code | [`infra/keycloak/finix-realm.json`](../infra/keycloak/finix-realm.json) | Realm, 3 clients, 7 roles, 5 demo users, imported by `--import-realm` |
| Keycloak adaptive SPI | [`infra/keycloak/spi/`](../infra/keycloak/spi/) | Java authenticator + service registration |
| Observability config | [`infra/monitoring/`](../infra/monitoring/) | Prometheus, alert rules, Loki, Alloy, Grafana datasources + dashboards, all provisioned from files |
| Database schema | `services/*/src/main/resources/db/migration/*.sql` | Flyway, versioned, applied on boot |
| Policy as code | [`policies/authz.rego`](../policies/authz.rego) + `authz_test.rego` | |

**How to verify.** Every file above is tracked; `git log` on `infra/` shows the change history. Grafana dashboards are provisioned with `allowUiUpdates: false`, so files beat in-UI edits on every restart.

**Manual / one-shot pieces — stated plainly.**
- The server is created **once** from cloud-init; there is no Terraform/Pulumi/Ansible in the repo, and re-running configuration management against a live host is not possible. Rebuilding the host is the only "re-apply".
- DNS A/AAAA records are manual (documented in the cloud-init header comments).
- `/opt/finix/monitoring.env` holds the Grafana admin password on the server and is deliberately untracked; the cloud-init comment instructs changing it after provisioning — whether that happened is not verifiable from the repo.
- `policies/authz.rego` is **not wired into any service** — no Kotlin code references OPA or port 8181. It runs only under `--profile security`.

---

## 4. Scalability, Availability & Reliability — 10%

**Implemented and verifiable in-tree.**

| Property | Where |
|---|---|
| Saga orchestration with compensation | `services/transaction-orchestrator/src/main/kotlin/org/finix/orchestrator/application/usecase/RunTransferSagaUseCase.kt` — reserve → post → commit → credit, `compensate()` releases holds |
| Idempotency on every mutating request | `services/shared-kernel/src/main/kotlin/org/finix/kernel/idempotency/IdempotencyFilter.kt` — missing key `400`, replay returns the recorded response, key reuse with a different body `422` |
| Transactional outbox | `services/shared-kernel/src/main/kotlin/org/finix/kernel/messaging/`; rationale in [ADR-0005](adr/0005-transactional-outbox-over-debezium.md) |
| Bounded downstream calls | `services/transaction-orchestrator/src/main/kotlin/org/finix/orchestrator/config/OrchestratorConfig.kt` (2s connect / 5s response) and `services/transaction-orchestrator/src/main/kotlin/org/finix/orchestrator/adapter/out/http/DownstreamCalls.kt` (10s call timeout) |
| Circuit breakers / retries | Resilience4j wired by `services/build-logic/src/main/kotlin/finix.spring-service.gradle.kts` |
| Dependency ordering | `depends_on: … condition: service_healthy` on the shared `x-jvm-service` anchor — no JVM service starts until postgres, redis, redpanda and keycloak pass their probes |
| Restart policy | `restart: unless-stopped` on all 12 application services (JVM ones inherit it from the `x-jvm-service` anchor) and on the whole monitoring profile. **Not** on `postgres`, `redis`, `redpanda`, `keycloak`, `vault` or `opa`, which fall back to Docker's default `no` |
| Chaos evidence | [`tests/chaos/ledger-down.sh`](../tests/chaos/ledger-down.sh) + [`tests/chaos/RESULTS.md`](../tests/chaos/RESULTS.md) — ledger killed mid-saga ends `COMPENSATED`/`FAILED`, never `COMPLETED`, no stranded hold |
| Ledger append-only enforcement | Flyway `BEFORE UPDATE OR DELETE` triggers, `services/ledger-service/src/main/resources/db/migration/V1__ledger.sql` |

**How to verify.** `./gradlew :transaction-orchestrator:test` (saga + compensation unit tests), then run the chaos script against a live stack.

**Honest limitations — do not oversell this category.**
- **Single host, single replica of everything.** No horizontal scaling, no load balancer, no orchestrator (no Kubernetes anywhere in the repo).
- **No backup or restore.** [`docs/runbooks/dr-failover.md`](runbooks/dr-failover.md) states Postgres volume loss means accepting the loss of all demo data and re-seeding; streaming backup is listed as Phase-3 backlog. Blueprint RTO/RPO targets in that runbook are **targets, not measurements**.
- **The datastores do not restart themselves.** `postgres`, `redis`, `redpanda`, `keycloak`, `vault` and `opa` carry no `restart:` key, so a crash of any of them stays down until someone intervenes — and every JVM service `depends_on` the first four.
- **No container resource limits.** Only JVM heap caps (`-Xmx192m`, enclave `-Xmx96m`) in the compose file — no `mem_limit`, no `deploy.resources`.
- **Load numbers are not a measured benchmark.** [`tests/load/RESULTS.md`](../tests/load/RESULTS.md) labels its own table a "plausible committed snapshot" at demo scale and explicitly disclaims the blueprint's 10k TPS.
- Risk scoring **fails open**: if risk-ai is unreachable, `RunTransferSagaUseCase` catches the exception and completes the transfer ungated, despite a log line saying it fails closed. Known, not yet fixed.

---

## 5. Operational Visibility & System Health — 15%

**Full stack, provisioned from files.**

| Component | Path |
|---|---|
| Prometheus + 10 scrape jobs | [`infra/monitoring/prometheus/prometheus.yml`](../infra/monitoring/prometheus/prometheus.yml) — `finix-jvm`, `finix-apps`, keycloak, redpanda, postgres, redis, node, cadvisor, loki, alloy |
| Alert rules | [`infra/monitoring/prometheus/rules/finix.yml`](../infra/monitoring/prometheus/rules/finix.yml) — `TargetDown`, `ServiceRestarting`, `HighHttpErrorRate`, `HighRequestLatency`, `JvmHeapPressure`, `HostDiskFillingUp`, `HostMemoryLow` |
| Grafana dashboards (17 + 3 panels) | [`infra/monitoring/grafana/dashboards/finix-overview.json`](../infra/monitoring/grafana/dashboards/finix-overview.json), [`finix-logs.json`](../infra/monitoring/grafana/dashboards/finix-logs.json) |
| Datasource + dashboard provisioning | [`infra/monitoring/grafana/provisioning/`](../infra/monitoring/grafana/provisioning/) — includes a `TraceID` derived field linking logs to traces |
| Log pipeline | [`infra/monitoring/loki/loki.yml`](../infra/monitoring/loki/loki.yml), [`infra/monitoring/alloy/config.alloy`](../infra/monitoring/alloy/config.alloy) (Docker socket discovery) |
| Exporters | node-exporter, cadvisor, postgres-exporter, redis-exporter in the compose `monitoring` profile |
| App instrumentation | `services/shared-kernel/src/main/kotlin/org/finix/kernel/config/ObservabilityAutoConfiguration.kt`; structured JSON logs via logstash-encoder; OTel trace propagation; correlation ids in `services/shared-kernel/src/main/kotlin/org/finix/kernel/web/CorrelationFilter.kt` |
| Metrics endpoints | `/actuator/prometheus` (JVM), `/metrics` (risk-ai, payment-hub, notification) |

Retention is explicit: Prometheus `30d` / `20GB` (compose `command:` flags). Only Grafana publishes a port, and only on loopback (`127.0.0.1:3002`), with Caddy terminating TLS in front.

**Five things to show judges live.**
1. Grafana → **FINIX / Service Overview** → *Targets up* and *Availability* — the whole mesh in one number.
2. Same dashboard → *5xx error ratio* and *Latency p95* while a transfer runs.
3. Grafana → **FINIX / Logs** → filter by service, click a `traceId` to jump across.
4. Prometheus → **Alerts** page showing the seven rules loaded and green.
5. `curl -s https://roboti.qzz.io/api/account/actuator/health` — the same probe compose and CI use.

**Honest limitations.** Alerts evaluate but have **no receiver** — no Alertmanager, so nothing pages anyone. No SLOs defined. Tracing is propagated and exported via OTLP but no collector/Jaeger is deployed, so there is no trace UI. Grafana ships with `admin` as the default username and a password supplied at provisioning time.

---

## 6. Security Practices & Protection of Sensitive Data — 15%

Categorised deliberately. **The single most important line in this document:**

> **Authentication is NOT currently enforced in production.** Every JVM service ships `finix.security.permit-all: true` in its own `application.yml`, and the production override sets only the Keycloak issuer URI — never `FINIX_SECURITY_PERMIT_ALL=false`. The running system therefore resolves `anyRequest().permitAll()`. Keycloak is deployed and validating nothing.

### IMPLEMENTED

| Capability | Evidence |
|---|---|
| Shamir 3-of-5 master key split over GF(256) + Feldman VSS | `services/vault-service/src/main/kotlin/org/finix/vault/domain/crypto/` — property tests in `services/vault-service/src/test/kotlin/org/finix/vault/domain/crypto/CryptoPropertyTest.kt` |
| Post-quantum primitives: ML-KEM-768, ML-DSA-65 | `services/shared-kernel/src/main/kotlin/org/finix/kernel/crypto/PostQuantum.kt`, BouncyCastle; rationale + BLS rejection in [ADR-0004](adr/0004-ml-dsa-anchor-signatures-instead-of-bls.md) |
| Tamper-evident ledger: SHA-256 hash chain, RFC 8785 canonical JSON, RFC 6962 Merkle anchors signed with ML-DSA-65 | `services/ledger-service/`, `services/shared-kernel/src/main/kotlin/org/finix/kernel/crypto/MerkleTree.kt`, `services/shared-kernel/src/main/kotlin/org/finix/kernel/crypto/Hashing.kt` |
| Independent verification without trusting the bank | `GET /api/v1/ledger/proof/{txId}` + [`scripts/verify-ledger.sh`](../scripts/verify-ledger.sh) + browser `apps/web/verify.html` |
| Append-only DB enforcement | Flyway `BEFORE UPDATE OR DELETE` triggers |
| Offline voucher signatures + replay protection | ECDSA P-256/SHA-256 over a canonical payload in `services/account-service/src/main/kotlin/org/finix/account/adapter/out/crypto/EcdsaVoucherSignatureVerifier.kt`; nonce reuse or sequence regression quarantines the device (`services/account-service/src/main/kotlin/org/finix/account/application/usecase/ReconcileOfflineVoucherUseCase.kt`), unit-tested |
| ML-assisted risk gate | `services/risk-ai-service/app/` — isolation forest blended with a transparent additive rules engine (`rules.py`); ALLOW / STEP_UP / BLOCK thresholds; model card at [`docs/model-card-risk-ai.md`](model-card-risk-ai.md) |
| DPoP (RFC 9449) proof verification | `services/shared-kernel/src/main/kotlin/org/finix/kernel/security/DPoPFilter.kt` — signature, `htm`/`htu`/`ath`, `iat` window, `jti` replay store, `cnf.jkt` binding |
| Idempotency against duplicate money movement | `services/shared-kernel/src/main/kotlin/org/finix/kernel/idempotency/IdempotencyFilter.kt` |
| Secrets kept out of the repo | `.gitignore` blocks `.env`, `*.pem`, `*.key`, `secrets/`; deployment secrets are GitHub Actions secrets (`SSH_PRIVATE_KEY`, `SSH_KNOWN_HOSTS`, `SSH_USER`, `SSH_HOST`); Grafana credentials live in an untracked server-side env file |
| TLS everywhere at the edge | Caddy automatic HTTPS for all four hostnames |

### CONFIGURABLE BUT NOT CURRENTLY ENFORCED

| Capability | Reality |
|---|---|
| JWT resource-server validation | Wired in `services/shared-kernel/src/main/kotlin/org/finix/kernel/security/SecurityAutoConfiguration.kt` with realm-role → `ROLE_*` mapping; the code default for `permit-all` is `false`, but all nine `application.yml` files override it to `true`. Flip the flag and it engages. |
| DPoP enforcement | `finix.dpop.required` defaults to `false` and **no service or compose file sets it to `true`**. The filter verifies a proof when one is presented; nothing requires one. (The KDoc claiming "production compose sets true" is inaccurate.) |
| Role-based authorisation | The realm defines `customer, sme, teller, compliance, regulator, admin, service-account` and users carry them, but there is **not one** `@PreAuthorize`/`hasRole`/`@Secured` in the codebase. Enabling auth today would yield *authenticated-can-do-anything*. |
| Service-to-service identity | The realm has a confidential `finix-services` client with service accounts enabled; **no application code requests a token**. Orchestrator→account/ledger, USSD→account/orchestrator and vault→enclave are plain unauthenticated HTTP. |
| OPA policy | `policies/authz.rego` + tests exist; no service calls OPA. |

### DEMO-SCALE

- Enclave: `services/enclave-runtime` runs read-only-rootfs with tmpfs and a **mock Nitro-format attestation document** signed by a build-time key ([`infra/enclave/README.md`](../infra/enclave/README.md)). **This is not an AWS Nitro Enclave.**
- Custodians are five logical identities, **not five physical HSMs**.
- HashiCorp Vault container exists under `--profile security` only; the core stack does not require it.
- Payment hub renders ISO 20022 pacs.008 from in-process connectors. **No LankaPay, no Visa, no live scheme connectivity.**
- Notifications render locale templates in memory. **No real SMS is sent.**

### PENDING HARDENING

- Turn `permit-all` off (sequencing is non-trivial: it would 401 every internal call and break the USSD telco callback — the audit for this is the prerequisite work).
- The `finix-services` client secret is committed in `infra/keycloak/finix-realm.json`. It reads as a placeholder-style value, but it is in git history and **must be rotated** before that client is used.
- Redirect URIs in the realm are `http://localhost:3000/*` and `http://localhost:3001/*` only — no production hostname is registered, so a browser login on the live host cannot complete today. `deploy.sh` rewrites `localhost` links in the static HTML with `sed`, which fixes navigation but not OAuth redirects.
- `X-Finix-User` (`services/identity-service/src/main/kotlin/org/finix/identity/adapter/in/rest/CurrentUser.kt`) is an identity-spoofing header that only exists for the demo; it must be deleted before auth goes on.
- Auth cookies are minted with `.secure(false)` (`services/identity-service/src/main/kotlin/org/finix/identity/adapter/in/rest/AuthController.kt`) for the local HTTP demo.
- Swagger UI, `/v3/api-docs` and `/actuator/prometheus` are in the shared `permitAll()` list.
- **Debug/tamper endpoint in production:** `LedgerTamperController` (`services/ledger-service/src/main/kotlin/org/finix/ledger/adapter/in/rest/LedgerController.kt`) is annotated `@Profile("dev", "default")`. Because no profile is ever selected, `default` is active — so `POST /api/v1/ledger/admin/tamper/{sequence}` **is live in production today**.
- **Demo seed endpoints in production:** `POST /api/v1/admin/seed` (account, identity) and `POST /api/v1/vault/admin/seed` (which wipes and re-splits the master key) are ungated on `master`. A fix gating all three behind `@Profile("dev")`, with 18 regression tests, is **IN WORKING TREE (UNCOMMITTED)** on this branch — see `services/account-service/src/test/kotlin/org/finix/account/security/SeedEndpointProfileTest.kt (plus identity-service and vault-service equivalents)`. Not yet on `master`, therefore **not yet true of production**.

---

## 7. Engineering Best Practices — 5%

| Practice | Evidence |
|---|---|
| Hexagonal architecture, enforced not just documented | `services/shared-kernel/src/testFixtures/kotlin/org/finix/kernel/test/HexagonalArchitecture.kt` — 8 ArchUnit rules (layering, framework-free domain, no field injection, ports are interfaces, use-case naming), asserted by a test in **every** service |
| Unit + property tests | e.g. `services/vault-service/src/test/kotlin/org/finix/vault/domain/crypto/CryptoPropertyTest.kt`, `services/ledger-service/src/test/kotlin/org/finix/ledger/domain/JournalEntryTest.kt`; Kotest + JUnit5 + MockK |
| Static analysis | detekt on every module (`config/detekt/detekt.yml`), SARIF uploaded to GitHub code scanning by CI |
| Coverage | JaCoCo via `finix.kotlin-base.gradle.kts` |
| Compiler strictness | `allWarningsAsErrors = true` — "a service that compiles dirty does not merge" |
| Convention plugins over copy-paste | `services/build-logic/src/main/kotlin/finix.{kotlin-base,spring-service,security,persistence,messaging}.gradle.kts` |
| ADRs | [`docs/adr/`](adr/) — 5 records, each explaining a rejected alternative |
| Runbooks | [`docs/runbooks/`](runbooks/) — key ceremony, incident response, DR failover |
| Honesty artefact | [`docs/FIDELITY-MATRIX.md`](FIDELITY-MATRIX.md) — claim-by-claim Implemented / demo-scale / deferred |
| Test strategy | [`docs/qa/TEST-STRATEGY.md`](qa/TEST-STRATEGY.md) |
| Repository layout | `services/`, `apps/`, `infra/`, `docs/`, `tests/`, `scripts/`, `policies/` — documented in README §Repository layout |

**How to verify.** `./gradlew verify` reproduces the CI gate locally.

**Limitations.**
- **Local validation only:** detekt 1.23.8 cannot analyse under the JDK 26 installed on one developer machine (fails with `> 26.0.2` on every module, including untouched ones). CI pins JDK 21, where detekt runs normally — this is a local toolchain limitation, **not a production or pipeline problem**.
- `integrationTest` source sets are declared by `services/build-logic/src/main/kotlin/finix.kotlin-base.gradle.kts` but contain **zero** files; there are no Testcontainers integration tests yet despite the dependencies being wired.
- `tests/contract/` contains a README only — no Pact tests.
- Playwright journeys in `tests/e2e/smoke.spec.md` are specified, not implemented.

---

## 8. Team Contributions — 5%

Roster as recorded in [`README.md`](../README.md) §Team Rexosphere. **Area and Evidence are intentionally left for the team to complete — this document does not assign contributions it cannot prove.**

| Contributor | Area | Evidence |
|---|---|---|
| Sangeeth Kariyapperuma ([@NipunSGeeTH](https://github.com/NipunSGeeTH)) | _to be completed_ | _to be completed_ |
| Kalana Liyanage ([@Kalana-Pankaja](https://github.com/Kalana-Pankaja)) | _to be completed_ | _to be completed_ |
| Ifaz Ikram ([@Ifaz-Ikram](https://github.com/Ifaz-Ikram)) | _to be completed_ | _to be completed_ |
| Suhas Dissanayake ([@SuhasDissa](https://github.com/SuhasDissa)) | _to be completed_ | _to be completed_ |

Objective evidence to fill the table from:
```bash
git shortlog -sne --all              # commits per author
git log --author="<name>" --oneline  # what each person actually landed
```
GitHub's Insights → Contributors and the PR list are the equivalent view for judges.

---

## LIVE JUDGE DEMO — 7 minutes

| # | Open | Say | Proves | Rubric |
|---|---|---|---|---|
| 1 | https://roboti.qzz.io (0:00–0:40) | "Public HTTPS, real deployment, four hostnames with automatic TLS from one cloud-init file." | Deployment is real, not a laptop | 1 Deployment |
| 2 | `/api/account/actuator/health` and the admin console (0:40–1:20) | "Twelve services behind two nginx proxies; every one exposes a health endpoint that the deploy script, CI and this page all poll." | Environment consistency, health | 1, 5 |
| 3 | Terminal: `POST /api/v1/transfers` LKR 100 farmer→SME (1:20–2:10) | "Saga: risk gate, reserve, ledger, commit. Idempotency-Key is mandatory — resend it and you get the same transfer back, not a second debit." | Money path + idempotency | 4 Reliability |
| 4 | Same terminal, `newDevice:true` then high velocity (2:10–3:00) | "Risk scored it STEP_UP, so the saga suspended; raise the amount and velocity and it BLOCKS before any money is reserved." | ML risk gate | 6 Security |
| 5 | `./scripts/verify-ledger.sh` + `apps/web/verify.html` (3:00–3:50) | "Hash chain, RFC 6962 Merkle root, ML-DSA-65 signature. You verify inclusion without trusting us." | Tamper-evident ledger | 6 Security |
| 6 | `tests/chaos/RESULTS.md` + run `ledger-down.sh` (3:50–4:30) | "Kill the ledger mid-saga: it compensates. No stranded hold, never a false COMPLETED. Rollback of a *deployment* is the honest gap — we revert and redeploy." | Failure handling; honest on rollback | 4 Reliability |
| 7 | Grafana → Service Overview, then Logs (4:30–5:20) | "Targets up, 5xx ratio, p95 latency, JVM heap; logs in Loki with a trace id that links back. Seven alert rules loaded — no pager wired yet." | Operational visibility | 5 Visibility |
| 8 | Admin console → vault ceremony (5:20–6:00) | "Shamir 3-of-5 with Feldman VSS, shards sealed with hybrid X25519 + ML-KEM-768, reconstructed only inside the enclave process. The attestation is Nitro-*format*, not AWS Nitro." | Key custody, PQC | 6 Security |
| 9 | `/ussd.html`, `/offline.html`, `/lite.html` (6:00–6:35) | "Same ledger from a feature phone on *334#, from an offline signed voucher whose replay quarantines the device, and from a sub-50 KB zero-JS page." | Inclusion across channels | 1, 4 |
| 10 | GitHub Actions: CI → Deploy → "Verify public endpoints" (6:35–7:00) | "CI gates deploy; deploy SSHes in and verifies the public front doors from outside. Being straight with you: production still rebuilds from source instead of pulling the image CI built — that's our top Phase-3 fix." | Build/release automation, honestly framed | 2 Build & Release |

Fallback if a service is down: [`docs/DEMO.md`](DEMO.md) has a per-symptom table. If time runs short, jump to step 8 — the ceremony is the closer.

---

## FINAL CHECKLIST

Tick only what has been verified at demo time. Items marked **(known false today)** are documented above, not hidden.

- [ ] Repository is public and reachable
- [ ] Live customer URL reachable — https://roboti.qzz.io
- [ ] Live admin URL reachable — https://admin.roboti.qzz.io
- [ ] Production read-only smoke green (record the run output; harness is uncommitted on this branch)
- [ ] All services healthy — `/actuator/health` per service (`docker compose ps` shows only `running` for the JVM services; they carry no Docker probe)
- [ ] No deliberate debug/tamper endpoint in production — **(known false today: `LedgerTamperController` is `@Profile("dev","default")` and `default` is active)**
- [ ] Demo seed endpoints absent in production — **(known false on `master`; fix uncommitted on this branch)**
- [ ] Authentication status honestly documented — **(done: §6 states permit-all is on)**
- [ ] CI green on the deployed commit
- [ ] Running deployment SHA recorded — **(no mechanism exists; record it manually from the Actions run)**
- [ ] Rollback tested — **(never exercised; no automated path)**
- [ ] Grafana accessible and dashboards loading
- [ ] Screenshots captured (dashboard, Grafana, ceremony, ledger proof, Actions run)
- [ ] Deployment documentation complete (`infra/cloud-init/user-data.yaml`, `docs/USER-GUIDE.md`, `docs/runbooks/`)
- [ ] No secrets in the repository — **(caveat: `finix-services` client secret is committed in the realm JSON; rotate)**
- [ ] No localhost in production browser flows — **(caveat: Keycloak redirect URIs are still localhost-only, so browser login cannot complete on the live host)**

---

*This document describes branch `comp/judge-tests`. Anything marked IN WORKING TREE (UNCOMMITTED) is not on `master` and therefore not in production. No credentials, tokens, keys or passwords appear anywhere in this file.*
