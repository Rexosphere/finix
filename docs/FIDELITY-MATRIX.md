# FINIX Fidelity Matrix

Honest map of Phase-1 RECON blueprint claims → what this repository actually ships.
Statuses:

| Status | Meaning |
|---|---|
| **Implemented** | Real algorithm / behaviour in tree; exercised by unit/integration or demo script |
| **Implemented at demo scale** | Real code path, deliberately scoped for a laptop compose demo (not production capacity / HSM / live scheme) |
| **Deferred to Phase 3** | Designed / ADRd / backlog; not claimed as running in `make demo` |

Evidence paths are relative to the repo root.

---

## Trust & crypto

| Phase-1 claim | Status | Evidence |
|---|---|---|
| Shamir 3-of-5 Master Key split (GF(256)) | Implemented | `services/vault-service/.../domain/crypto/` · property tests in vault-service |
| Feldman VSS (forged shard detection) | Implemented | `services/vault-service/.../FeldmanVss.kt` · reconstruct path verifies commitments |
| Hybrid sealed custodian shards (X25519 + ML-KEM-768) | Implemented at demo scale | vault-service seal/unseal; local keys, not production HSM |
| 5 custodians (CB / Govt DR / IEEE / Cloud HSM A+B) | Implemented at demo scale | `CustodianId.kt` · ceremony UI `apps/admin` · logical custodians, not 5 physical HSMs |
| Enclave reconstruct-only + key zeroing | Implemented at demo scale | `services/enclave-runtime` — read-only rootfs, tmpfs, `POST /attest` + `/reconstruct`; **mock** Nitro-format attestation, not AWS Nitro |
| Enclave attestation verified before shard release | Implemented at demo scale | vault → enclave client; mock cert chain at build time (`infra/enclave/README.md`) |
| Account recovery 2-of-3 social shares (FR-01) | Deferred to Phase 3 | Same primitives reusable; no product recovery UI/API yet |
| ML-KEM-768 / ML-DSA-65 (PQC) | Implemented | `services/shared-kernel` BouncyCastle adapters · ADR-0004 |
| BLS aggregate anchor signatures | Deferred to Phase 3 | **Explicitly not implemented** — ADR-0004 (no BC BLS provider; not PQ-safe) |
| Hashicorp Vault PKI / transit | Implemented at demo scale | compose `--profile security` Vault container; core demo does not require it |

---

## Ledger & integrity

| Phase-1 claim | Status | Evidence |
|---|---|---|
| Double-entry journals | Implemented | `services/ledger-service` · balance checks |
| Append-only DB enforcement | Implemented | Flyway triggers `BEFORE UPDATE OR DELETE` · demo via psql / tamper endpoint |
| SHA-256 hash chain + RFC 8785 JCS | Implemented | shared-kernel JCS + ledger chain · `scripts/verify-ledger.sh` |
| Merkle window + ML-DSA-65 signed anchor | Implemented at demo scale | `POST /api/v1/ledger/anchors/now` · scheduled window in service; local signer |
| Inclusion proof API | Implemented | `GET /api/v1/ledger/proof/{txId}` · browser `apps/web/verify.html` |
| Independent verify script | Implemented | `scripts/verify-ledger.sh` |
| Tamper pinpoint demo | Implemented | `POST /api/v1/ledger/admin/tamper/{sequence}` |
| Hyperledger Fabric anchor adapter | Deferred to Phase 3 | Planned `FabricAnchorAdapter` / `--profile fabric`; **no fabric tree in repo yet** |
| 10k TPS sustained | Deferred to Phase 3 | Demo-scale k6 only — see `tests/load/RESULTS.md` |

---

## Money movement & saga

| Phase-1 claim | Status | Evidence |
|---|---|---|
| Account reserve / commit / release holds | Implemented | `services/account-service` |
| Internal-transfer saga + compensation | Implemented | `services/transaction-orchestrator` · `tests/chaos/ledger-down.sh` |
| Transactional outbox → Kafka/Redpanda | Implemented at demo scale | outbox in shared-kernel · Redpanda in compose · ADR-0005 (Debezium CDC deferred) |
| Idempotency keys | Implemented | shared-kernel `IdempotencyFilter` |
| Database-per-service | Implemented at demo scale | logical DBs in one Postgres (`ADR-0003`); physical isolation under Phase-3 profile |

---

## Offline & inclusion channels

| Phase-1 claim | Status | Evidence |
|---|---|---|
| Offline vouchers (signed device seq + nonce) | Implemented at demo scale | account-service offline API · `apps/web/js/offline.js` · WebCrypto (simulates SE) |
| Double-spend quarantine | Implemented | nonce reuse → quarantine · unit tests |
| QR / Base45 voucher encoding | Implemented at demo scale | web PWA offline path |
| NFC P2P | Deferred to Phase 3 | Not wired in demo web |
| USSD `*334#` Africa's Talking contract | Implemented | `services/ussd-gateway` `POST /ussd` · simulator `apps/web/ussd.html` |
| Low-data `/lite` &lt; 50 KB | Implemented | `apps/web/lite.html` · `scripts/check-lite-budget.sh` in CI |
| Full Next.js PWA + next-intl trilingual a11y suite | Implemented at demo scale | Static PWA shell under `apps/web` (not a full Next.js App Router deploy); Sinhala/Tamil copy present in USSD/notifications; axe/Playwright a11y CI not yet greenfield |

---

## Identity & adaptive auth

| Phase-1 claim | Status | Evidence |
|---|---|---|
| Keycloak OIDC realm-as-code | Implemented | `infra/keycloak/finix-realm.json` |
| PKCE BFF cookies | Implemented at demo scale | identity-service `/api/v1/auth/*` |
| DPoP | Implemented at demo scale | shared-kernel filter; **disabled** (`permit-all`) on compose demo profile |
| OPA Rego policies | Implemented at demo scale | `policies/` + compose `--profile security` |
| Adaptive auth SPI (login risk → step-up) | Implemented at demo scale | `infra/keycloak/spi` · calls identity/risk; mount into Keycloak for full demo (default image may omit custom JAR until SPI build step) |
| WebAuthn / TOTP production MFA | Implemented at demo scale | Keycloak realm supports; transfer step-up demo accepts **any non-blank OTP** (`RunTransferSagaUseCase`) |
| 5-minute token / continuous verification story | Implemented at demo scale | realm token TTLs; continuous device posture is partial |

---

## Risk AI & AI Shield

| Phase-1 claim | Status | Evidence |
|---|---|---|
| IsolationForest + rules scoring | Implemented at demo scale | `services/risk-ai-service` · synthetic training script · `docs/model-card-risk-ai.md` |
| Saga gating allow / step_up / block | Implemented | orchestrator ↔ risk `/v1/score` |
| Compliance case on block | Implemented at demo scale | risk opens in-memory case; compliance `POST /api/v1/cases/from-risk` available |
| AI Shield quarantine | Implemented at demo scale | `/v1/shield/*` in-process; **not** Istio AuthorizationPolicy |
| Federated learning (FedAvg) | Implemented at demo scale | `GET /v1/federated/demo` — toy two-bank demonstrator only |
| Production model monitoring / drift | Deferred to Phase 3 | — |

---

## Breadth services (M8)

| Phase-1 claim | Status | Evidence |
|---|---|---|
| Payment Hub ISO 20022 pacs.008 | Implemented at demo scale | `services/payment-hub` Go · in-memory connectors (LankaPay/Visa/CBDC stubs) |
| Loan apply + decide + schedule | Implemented at demo scale | `services/loan-service` |
| Compliance screening + cases | Implemented at demo scale | `services/compliance-service` · demo screening list |
| Notifications (en/si/ta) | Implemented at demo scale | `services/notification-service` · in-memory, no telco SMS |
| Admin console (ceremony) | Implemented at demo scale | `apps/admin` ceremony UI; broader ops console (saga inspector, SAR, feature flags) deferred |

---

## Platform / ops (blueprint “production fabric”)

| Phase-1 claim | Status | Evidence |
|---|---|---|
| `docker compose` graded runnable target | Implemented | `infra/compose/docker-compose.yml` · `make demo` |
| Kong API Gateway | Deferred to Phase 3 | Services exposed directly on localhost ports for demo |
| Istio mTLS STRICT + AuthorizationPolicy | Deferred to Phase 3 | Zero-trust story expressed in ADRs; no `infra/k8s` charts in tree yet |
| EKS + GKE multi-region | Deferred to Phase 3 | — |
| Terraform / GitOps full stack | Deferred to Phase 3 | Compose + Gradle are the Phase-2 contract |
| Nitro Enclaves (real) | Deferred to Phase 3 | Mock attestation only |
| Chaos / load / DR evidence | Implemented at demo scale | `tests/chaos` · `tests/load` · runbooks · committed RESULTS |
| Pact contract tests | Deferred to Phase 3 | Placeholder noted in `docs/qa/TEST-STRATEGY.md` |
| ZAP / Trivy / full security CI | Implemented at demo scale | CI today: `./gradlew verify` + lite budget + compose config; ZAP under security profile planned |

---

## How to read this

If a judge asks “is X real?”, find the row. **Implemented** and **Implemented at demo scale** have file paths you can open. **Deferred to Phase 3** is backlog, not vapourware dressed as green.

Primary ADRs that document deliberate non-claims: [0003](adr/0003-logical-database-per-service.md), [0004](adr/0004-ml-dsa-anchor-signatures-instead-of-bls.md), [0005](adr/0005-transactional-outbox-over-debezium.md).
