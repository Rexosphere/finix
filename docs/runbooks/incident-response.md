# Runbook — Incident response

**Audience:** on-call / demo operators. **Scope:** compose-based FINIX demo stack, not production SRE.

## Severity (demo)

| Sev | Meaning | Response time |
|---|---|---|
| SEV-1 | Money path broken (orchestrator / account / ledger) or vault unlock blocked during ceremony | Drop everything; restore health |
| SEV-2 | Channel degraded (USSD / web / notify) | Mitigate; continue other demo beats |
| SEV-3 | Observability / non-critical polyglot | Note and continue |

## First 5 minutes

1. **Declare** the symptom in one sentence (e.g. “transfers 503”).
2. **Health sweep:** `bash tests/e2e/smoke.sh`
3. **Logs:** `make logs` or `docker compose -f infra/compose/docker-compose.yml --profile core logs --tail=120 <service>`
4. **Do not** restart Postgres with `-v` unless you accept full re-seed (`make down` wipes volumes).

## Common incidents

### Transfers stuck / 5xx

- Check `transaction-orchestrator`, `account-service`, `ledger-service`, `risk-ai-service`.
- Risk down → saga fail-closes to step-up; say so to judges, complete OTP.
- Duplicate Idempotency-Key → change key; do not retry same key expecting a new saga.
- Mid-saga ledger death → expect `COMPENSATED` (`tests/chaos/ledger-down.sh`).

### Ledger verify fails

- If after **tamper demo**, expected — restore by re-seeding DB (`make down && make demo`) or continue narrative.
- If unexpected: dump `GET /api/v1/ledger/verify`, capture `firstBreakSequence`, stop writing journals until root-caused.

### Vault ceremony errors

- Re-seed: `POST /api/v1/vault/admin/seed?force=true` (or UI Seed).
- Enclave down → reconstruct fails; restart `enclave-runtime`, re-approve if state requires.
- Never paste reconstructed key material into chat logs — egress is network-config only by design.

### USSD / offline anomalies

- Unknown phone → `END` not registered (directory is demo-fixed).
- Offline quarantine → intentional after double-spend; re-register device with new id for clean demo.

## Comms template

```
INCIDENT: <one-liner>
IMPACT: <transfers | ceremony | channel>
STATUS: investigating | mitigated | resolved
NEXT: <action + ETA>
```

## Aftercare

- File what broke in team notes; update fidelity matrix if a claim was overstated.
- Re-run `bash tests/e2e/smoke.sh` before inviting judges back.
