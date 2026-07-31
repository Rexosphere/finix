# Runbook — Master Key ceremony

**Purpose:** Unlock the banking network config by reconstructing the Shamir-split Master Key **inside the enclave**. The key must never appear in UI, logs, or API responses.

## Preconditions

- `vault-service` (:8086) and `enclave-runtime` (:8090) healthy
- Admin UI http://localhost:3001 **or** curl access
- At least three custodian operators available (demo: one person clicking three Approve buttons)

## Happy path (UI)

1. Open http://localhost:3001
2. **Seed / start ceremony** — creates 5 sealed shards + Feldman commitments
3. Custodians approve until threshold **3 of 5** (`THRESHOLD_MET`)
4. **Reconstruct in enclave**
5. Confirm banner unlock + egress log shows **network-config plaintext only**
6. State should be `UNLOCKED`

## Happy path (API)

```bash
curl -sS -X POST http://localhost:8086/api/v1/vault/admin/seed \
  -H "Idempotency-Key: ceremony-seed-1"

for c in CENTRAL_BANK GOVT_DR IEEE_VAULT; do
  curl -sS -X POST "http://localhost:8086/api/v1/vault/ceremony/approve/${c}" \
    -H "Idempotency-Key: approve-${c}"
done

curl -sS -X POST http://localhost:8086/api/v1/vault/ceremony/reconstruct \
  -H "Idempotency-Key: reconstruct-1" | python3 -m json.tool

curl -sS http://localhost:8086/api/v1/vault/ceremony/egress-log | python3 -m json.tool
```

## Security controls (demo-scale)

| Control | Behaviour |
|---|---|
| Threshold | Any 2 shards insufficient; 3 required |
| Feldman VSS | Forged shard rejected before reconstruct |
| Enclave | Reconstruct-only API; mock attestation verified by vault |
| Egress | Network-config only; Master Key zeroed after use |
| Custodians | Logical identities — not separate air-gapped HSMs |

## Abort / reset

- Ceremony wedged: `POST /api/v1/vault/admin/seed?force=true`
- Compromised demo shard narrative: stop, re-seed, explain VSS rejection in unit tests if live forge not shown
- **Never** commit real production master keys into this repo

## Related

- [USER-GUIDE.md](../USER-GUIDE.md) §6
- [DEMO.md](../DEMO.md) beat 12
- `infra/enclave/README.md`
