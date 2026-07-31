# Runbook — DR failover (demo scale)

**Targets (blueprint):** RTO ≤ 15 min · RPO ≤ 1 min.  
**Honesty:** this Phase-2 stack proves **recoverability of the compose demo**, not multi-region EKS/GKE failover. See fidelity matrix (Istio / multi-region **Deferred to Phase 3**).

## What “DR” means here

| Scenario | Demo action | Approx RTO |
|---|---|---|
| Single JVM service crash | Compose restart of that service | &lt; 2 min |
| Ledger mid-saga outage | Compensation (`COMPENSATED`) + ledger restart | &lt; 3 min |
| Full stack loss (demo laptop) | `make down && make demo` + re-seed | 5–10 min cold |
| Postgres volume loss | Accept RPO = all demo data; re-seed personas | = full recreate |

## Drill A — Service restart

```bash
docker compose -f infra/compose/docker-compose.yml --profile core restart ledger-service
# wait health
curl -sf http://localhost:8084/actuator/health
# prove money path
curl -sS -X POST http://localhost:8085/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: dr-$(date +%s)" \
  -d '{"fromAccountId":"a2222222-2222-4222-8222-222222222201","toAccountId":"a2222222-2222-4222-8222-222222222202","amount":"LKR 5.00"}'
```

**Pass:** health UP; transfer completes or cleanly step-ups.

## Drill B — Saga compensation (logical DR)

```bash
FROM_ACCOUNT=a2222222-2222-4222-8222-222222222201 \
TO_ACCOUNT=a2222222-2222-4222-8222-222222222202 \
bash tests/chaos/ledger-down.sh
```

**Pass:** `COMPENSATED` / `FAILED`; balances consistent; ledger returns.

## Drill C — Cold recreate

```bash
make down
make demo
bash tests/e2e/smoke.sh
./scripts/verify-ledger.sh
```

**Pass:** personas seeded; smoke has `ok > 0`; ledger verify valid (empty or fresh chain).

## RPO notes

- Demo Postgres is a **single volume**. Destroying it loses journals, sagas, vouchers.
- Outbox + Redpanda are best-effort on one node — not a cross-AZ commit log.
- Phase-3 backlog: streaming backup, multi-AZ Postgres, Fabric/external anchor for cross-site proof.

## Record template

| Field | Example |
|---|---|
| Date | 2026-07-31 |
| Drill | B — ledger-down |
| Start / end | 20:10 / 20:12 |
| RTO measured | 2 min |
| RPO measured | 0 (compensated; no false commit) |
| Result | PASS |
| Gaps | No multi-region |

## Related

- [tests/chaos/RESULTS.md](../../tests/chaos/RESULTS.md)
- [incident-response.md](incident-response.md)
