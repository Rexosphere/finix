# FINIX Test Strategy

Hackathon-scale pyramid: prove the heroes thoroughly, smoke the edges, defer industrial contract/security suites to Phase 3 with placeholders so judges see intent.

```
                 ┌──────────────┐
                 │  Playwright  │  journeys (planned) · smoke.sh today
                 │  k6 · chaos  │  demo-scale evidence committed
                 ├──────────────┤
                 │ Testcontainers│  integration source set (Docker)
                 │ Pact (later) │  placeholder consumer/provider
                 ├──────────────┤
                 │ Unit · prop  │  Kotest · ArchUnit · pytest · go test
                 │ detekt/JaCoCo│  ≥80% line / ≥70% branch on hexagon
                 └──────────────┘
```

## Unit / ArchUnit / property

| Layer | Tooling | What it guards |
|---|---|---|
| shared-kernel | Kotest + property | Money, JCS, Merkle, Shamir/GF helpers, DPoP, outbox |
| Each JVM service | Kotest + ArchUnit | Domain rules; hexagonal dependency direction (`HexagonalArchitectureTest`) |
| risk-ai | pytest | Rules engine thresholds (`tests/test_rules.py`) |
| payment-hub | `go test ./...` | pacs.008 encode + HTTP handlers |
| notification | node test + `scripts/smoke.js` | templates / notify |

Gate: `./gradlew verify` (warnings-as-errors, detekt, scoped coverage). **Not** “100% repo coverage” — adapters rely on integration tests.

## Testcontainers

`./gradlew integrationTest` — real Postgres / Kafka / Keycloak where suites exist. Excluded from default `check` so laptops without Docker still pass `verify`.

## Pact (placeholder)

Directory: `tests/contract/`. Phase-3 intent: consumer contracts for orchestrator→account/ledger/risk and provider verification in CI. **Not wired yet** — fidelity matrix marks Deferred.

## Playwright

Journey specs described in `tests/e2e/smoke.spec.md` (login → transfer → verify → offline → USSD → ceremony). Automation not required for M9 evidence; bash `tests/e2e/smoke.sh` covers health.

## k6

`tests/load/transfer.js` — transfer (or health fallback). Results: `tests/load/RESULTS.md` (demo-scale, not 10k TPS).

## ZAP / security scans

Compose `--profile security` hosts OPA (+ Vault). OWASP ZAP baseline against the gateway surface is Phase-3 CI. Today: no secrets in dist zip; DPoP/OPA code present; demo `permit-all`.

## Chaos

`tests/chaos/ledger-down.sh` — kill ledger mid-saga → expect `COMPENSATED` / `FAILED`. Write-up: `tests/chaos/RESULTS.md`.

## What we deliberately do not claim

- Production soak / 10k TPS
- Full Playwright video artifact in CI (planned, not green yet)
- Mutation testing (PIT) as a merge blocker
- Live telco / scheme certification
