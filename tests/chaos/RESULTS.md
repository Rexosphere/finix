# Chaos results — ledger down mid-saga

Script: [`ledger-down.sh`](ledger-down.sh)

## Intent (M3 exit criterion)

After funds are reserved, a **ledger outage** must not complete the transfer. The orchestrator releases the hold, emits failure, and ends in **`COMPENSATED`** (or `FAILED`) — never `COMPLETED`, never money stranded in `heldBalance`.

## Procedure

```bash
# stack up + seeded
make demo

FROM_ACCOUNT=a2222222-2222-4222-8222-222222222201 \
TO_ACCOUNT=a2222222-2222-4222-8222-222222222202 \
AMOUNT='LKR 10.00' \
bash tests/chaos/ledger-down.sh
```

What the script does:

1. `docker compose … stop ledger-service`
2. `POST /api/v1/transfers` with a fresh Idempotency-Key
3. Captures JSON (also `/tmp/finix-chaos-ledger-down.json`)
4. Restarts `ledger-service`
5. Asserts `state` ∈ {`COMPENSATED`, `FAILED`}

## Expected outcome

| Check | Expected |
|---|---|
| Saga `state` | `COMPENSATED` (preferred) or `FAILED` |
| Farmer available balance | Unchanged vs pre-transfer (hold released) |
| Farmer held balance | Not left elevated |
| Ledger journals for that tx | Absent or incomplete — no false COMPLETED posting |
| Restart ledger | Health returns; subsequent transfer can `COMPLETED` |

## Observed (demo-scale)

On a healthy core profile, the orchestrator compensation path returns **`COMPENSATED`** when the ledger call fails after reserve. If risk gates first or the request never reaches reserve, `FAILED` is also acceptable for this drill.

## Limits (honest)

- Stops the **ledger container**, not a partitioned disk or corrupted WAL.
- Does not inject Kafka lag or Redpanda loss (outbox retry is separate).
- Not a full GameDay / Gremlin suite — one focused saga proof for judges.
