# FINIX User Guide

Practical walkthrough for judges. Needs **JDK 21** + **Docker**.

```bash
git clone https://github.com/Rexosphere/finix.git
cd finix
make demo          # bootJars → compose --profile core → health wait → seed → URL table
```

Stop/restart: `make down` · `make up` · `make seed` · `make logs`.  
Gates (optional): `./gradlew verify` · `bash tests/e2e/smoke.sh`.

Docs: [DEMO.md](DEMO.md) · [FIDELITY-MATRIX.md](FIDELITY-MATRIX.md) · [qa/TEST-STRATEGY.md](qa/TEST-STRATEGY.md) · [runbooks/](runbooks/).

---

## URLs / ports

| Surface | URL | Notes |
|---|---|---|
| Web / lite / verify | http://localhost:3000 | `apps/web` |
| Admin ceremony | http://localhost:3001 | Vault UI |
| Keycloak | http://localhost:8081 | user `admin`; password from `./scripts/gen-secrets.sh --show` |
| Identity | :8082 | |
| Account | :8083 | seed + offline |
| Ledger | :8084 | verify / proof |
| Orchestrator | :8085 | transfers |
| Vault | :8086 | ceremony API |
| USSD | :8087 | `POST /ussd` |
| Loan | :8088 | |
| Compliance | :8089 | |
| Enclave | :8090 | |
| Risk AI | :8091 | `/health` |
| Payment Hub | :8092 | pacs.008 |
| Notifications | :8093 | |
| Kafka | :19092 | Redpanda |

Compose demo uses `finix.security.permit-all: true` — curls need no Bearer token.

---

## Personas

Password: **`Finix!2026`**

| User | Account id | Number | Opening |
|---|---|---|---|
| `farmer@finix.lk` | `a2222222-…2201` | `FINIX-SAV-00000001` | LKR 25,000 |
| `sme@finix.lk` | `a2222222-…2202` | `FINIX-CUR-00000002` | LKR 150,000 |
| `elder@finix.lk` | `a2222222-…2203` | `FINIX-SAV-00000003` | LKR 80,000 |
| `regulator@finix.lk` | — | — | — |

Full UUIDs: `a2222222-2222-4222-8222-222222222201` / `…202` / `…203`.  
USSD phones: `+94771110001` / `002` / `003`.

---

## Internal transfer

```bash
curl -sS -X POST http://localhost:8085/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-$(date +%s)" \
  -d '{
    "fromAccountId":"a2222222-2222-4222-8222-222222222201",
    "toAccountId":"a2222222-2222-4222-8222-222222222202",
    "amount":"LKR 100.00"
  }' | python3 -m json.tool
```

**Expect:** `"state":"COMPLETED"`. Check balance: `GET :8083/api/v1/accounts/<id>`.

---

## Ledger verify (browser + script)

- Browser: http://localhost:3000/verify.html  
- Script: `./scripts/verify-ledger.sh` · optional `./scripts/verify-ledger.sh <txId>`

**Expect:** `valid=true`. Tamper then re-verify:

```bash
curl -sS -X POST http://localhost:8084/api/v1/ledger/admin/tamper/1
./scripts/verify-ledger.sh    # valid=false, firstBreakSequence set
curl -sS -X POST http://localhost:8084/api/v1/ledger/anchors/now
```

---

## Vault ceremony

UI: http://localhost:3001 → **Seed** → approve **3 of 5** custodians → **Reconstruct**.  
Banner unlocks; egress log = network-config only (never the Master Key).

```bash
curl -sS -X POST http://localhost:8086/api/v1/vault/admin/seed -H "Idempotency-Key: v1"
for c in CENTRAL_BANK GOVT_DR IEEE_VAULT; do
  curl -sS -X POST http://localhost:8086/api/v1/vault/ceremony/approve/$c -H "Idempotency-Key: a-$c"
done
curl -sS -X POST http://localhost:8086/api/v1/vault/ceremony/reconstruct -H "Idempotency-Key: r1"
```

**Expect:** `PENDING` → `THRESHOLD_MET` → `UNLOCKED`.

---

## Offline voucher + double-spend

Web: http://localhost:3000 (offline QR / outbox).  
API: `POST :8083/api/v1/offline/devices` then `…/vouchers/reconcile`.  
**Expect:** second reconcile with the **same nonce** quarantines the device. Unit proof: `ReconcileOfflineVoucherUseCaseTest`.

---

## USSD `*334#`

Simulator: http://localhost:3000/ussd.html · lite: `/lite.html` (&lt;50 KB CI).

```bash
curl -sS -X POST http://localhost:8087/ussd \
  -d 'sessionId=d1&phoneNumber=%2B94771110001&serviceCode=%2A334%23&text='
curl -sS -X POST http://localhost:8087/ussd \
  -d 'sessionId=d1&phoneNumber=%2B94771110001&serviceCode=%2A334%23&text=1'
```

Menu: 1 Balance · 2 Send · 3 Mini-statement · 4 Loan · 5 Language. **Expect:** `CON`/`END` lines.

---

## Risk step-up / block

```bash
# step-up
curl -sS -X POST http://localhost:8085/api/v1/transfers \
  -H 'Content-Type: application/json' -H "Idempotency-Key: step-$(date +%s)" \
  -d '{"fromAccountId":"a2222222-2222-4222-8222-222222222201","toAccountId":"a2222222-2222-4222-8222-222222222202","amount":"LKR 100.00","newDevice":true}'
# → AWAITING_STEP_UP; then:
curl -sS -X POST http://localhost:8085/api/v1/transfers/<id>/step-up \
  -H 'Content-Type: application/json' -d '{"otpCode":"123456"}'   # any non-blank OTP

# block (high risk)
curl -sS -X POST http://localhost:8085/api/v1/transfers \
  -H 'Content-Type: application/json' -H "Idempotency-Key: block-$(date +%s)" \
  -d '{"fromAccountId":"a2222222-2222-4222-8222-222222222202","toAccountId":"a2222222-2222-4222-8222-222222222201","amount":"LKR 100000.00","newDevice":true,"velocity1h":8}'
# → BLOCKED
```

Model card: `docs/model-card-risk-ai.md`.

---

## AI Shield quarantine

```bash
curl -sS -X POST http://localhost:8091/v1/shield/quarantine \
  -H 'Content-Type: application/json' \
  -d '{"service":"payment-hub","reason":"demo-anomaly"}'
curl -sS http://localhost:8091/v1/shield/quarantines
curl -sS -X POST http://localhost:8091/v1/shield/release/payment-hub
```

Demo-scale in-process shield — Istio DENY is Phase 3.

---

## Loan apply

```bash
curl -sS -X POST http://localhost:8088/api/v1/loans \
  -H 'Content-Type: application/json' -H "Idempotency-Key: loan-$(date +%s)" \
  -d '{"borrowerUserId":"a1111111-1111-4111-8111-111111111101","accountId":"a2222222-2222-4222-8222-222222222201","principal":"LKR 5000.00","termMonths":12}'
curl -sS -X POST http://localhost:8088/api/v1/loans/<id>/decide \
  -H 'Content-Type: application/json' -d '{"riskHint":"allow"}'
```

**Expect:** created loan + decided status with repayment schedule.

---

## Payment hub pacs.008

```bash
curl -sS http://localhost:8092/health
PAY=$(curl -sS -X POST http://localhost:8092/v1/payments \
  -H 'Content-Type: application/json' \
  -d '{"debtorAccount":"LK001","creditorAccount":"LK002","amountMinor":150000,"currency":"LKR","endToEndId":"E2E-42","scheme":"LANKAPAY"}')
ID=$(echo "$PAY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl -sS "http://localhost:8092/v1/payments/${ID}/pacs008"
```

**Expect:** ISO 20022-shaped pacs.008 from in-process connectors (not a live scheme).

---

## Notifications

```bash
curl -sS -X POST http://localhost:8093/v1/notify \
  -H 'Content-Type: application/json' \
  -d '{"channel":"sms","locale":"si","template":"transfer_receipt","to":"+94771110001","vars":{"amount":"LKR 100.00","payee":"SME"}}'
curl -sS http://localhost:8093/v1/messages
```

**Expect:** rendered `en`/`si`/`ta` body in memory (no real SMS).

---

## Chaos (optional)

```bash
FROM_ACCOUNT=a2222222-2222-4222-8222-222222222201 \
TO_ACCOUNT=a2222222-2222-4222-8222-222222222202 \
bash tests/chaos/ledger-down.sh
```

**Expect:** `COMPENSATED` or `FAILED` — see `tests/chaos/RESULTS.md`.
