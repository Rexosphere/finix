# FINIX 12-minute judge demo

Scripted narrative ending in the **vault ceremony**. Keep a second laptop on `make logs` if something stalls.

**Prep (before judges sit down):** `make demo` already green; browser tabs open for web `:3000`, admin `:3001`, Keycloak `:8081`. Seeded account IDs on a sticky note.

| Beat | Clock | What you show | Say / do |
|---|---|---|---|
| 0 · Cold start | 0:00–0:45 | Terminal | “Graded path is `make demo` — compose core, health wait, seed.” Point at URL table. |
| 1 · Personas | 0:45–1:30 | Keycloak or sticky | Login story: `farmer@finix.lk` / `Finix!2026`. Three inclusion personas + regulator. |
| 2 · Adaptive step-up | 1:30–2:30 | Terminal | POST transfer with `"newDevice": true` → `AWAITING_STEP_UP`. Complete with OTP `123456` → `COMPLETED`. “Risk AI gates the saga; MFA resumes it.” |
| 3 · Happy transfer | 2:30–3:15 | Terminal / web | Normal LKR 100 farmer→SME → `COMPLETED`. Show account balances moved. |
| 4 · Ledger proof | 3:15–4:30 | `verify.html` + script | Run `./scripts/verify-ledger.sh`. Open http://localhost:3000/verify.html. “Hash chain + Merkle + ML-DSA — you verify without trusting the bank.” |
| 5 · Tamper | 4:30–5:15 | Terminal | `POST .../ledger/admin/tamper/{seq}` then re-verify → `valid=false`, break at exact sequence. |
| 6 · Offline | 5:15–6:15 | Web PWA | Airplane / offline mode: create voucher QR. Reconnect. Reconcile. Deliberate **double-spend** of same nonce → device quarantined. |
| 7 · USSD `*334#` | 6:15–7:15 | `ussd.html` or curl | Dial `*334#` on simulator as `+94771110001` → Balance. Optional Send Money. “Same Africa’s Talking contract a telco would POST.” Show `/lite.html` for &lt;50 KB. |
| 8 · Fraud block | 7:15–8:00 | Terminal | High velocity + large amount → `BLOCKED`. Optional: `GET :8091/v1/cases`. |
| 9 · AI Shield | 8:00–8:45 | Terminal | Quarantine `payment-hub` via `/v1/shield/quarantine`, list active, release. “Demo-scale shield; Istio DENY is Phase 3.” |
| 10 · Breadth (pick 1) | 8:45–9:30 | Terminal | **Either** loan apply `:8088` **or** payment hub pacs.008 `:8092` **or** notify Sinhala SMS `:8093`. One beat only — don’t boil the ocean. |
| 11 · Chaos (optional if ahead) | 9:30–10:15 | Terminal | `tests/chaos/ledger-down.sh` → `COMPENSATED`. “No stranded holds.” Skip if time-tight. |
| 12 · Vault ceremony | 10:15–12:00 | http://localhost:3001 | Seed → approve **3 of 5** custodians → Reconstruct. Banner unlocks. Egress log = network-config only. Close: “Master Key never left the enclave — banking mesh, not a monolith.” |

---

## Fallback if a service is down

| Symptom | Move |
|---|---|
| Orchestrator 5xx | `docker compose … logs transaction-orchestrator --tail=80`; retry with fresh Idempotency-Key |
| Risk unreachable | Saga fail-closes to step-up — still demoable; say so honestly |
| Admin UI blank | Hit vault API curls from USER-GUIDE §6 |
| Web offline broken | Show unit test name + account offline reconcile curl narrative |
| Out of time | Jump straight to **beat 12** — ceremony is the narrative closer |

---

## One-liner closing

> FINIX rebuilt banking as a mesh: verifiable ledger, offline inclusion, risk-gated money movement, and a Shamir vault that only unlocks inside an enclave — and the fidelity matrix tells you exactly what is demo-scale vs Phase 3.
