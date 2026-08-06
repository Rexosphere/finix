# FINIX e2e harness

| File | What it is |
|---|---|
| `finix-smoke.sh` | **The harness.** Validates a running FINIX environment through its public edge, given a `BASE_URL`. |
| `selftest.sh` | Validates the harness itself against a mock edge. No Docker, no deployment. |
| `lib/finix_smoke_helper.py` | JSON / exact-money / RFC-6962 Merkle / redaction helpers used by the harness. |
| `lib/mock_finix.py` | Stand-in FINIX edge used only by `selftest.sh`, with fault injection. |
| `smoke.sh` | The original soft health check against fixed `localhost` ports. Still works; superseded by `finix-smoke.sh`. |
| `smoke.spec.md` | Planned Playwright journeys (not implemented). |

## Run it

```bash
# Read-only: GET/HEAD only, cannot change a byte. Safe against any environment.
bash tests/e2e/finix-smoke.sh http://localhost:3000 --read-only

# Full: read-only checks plus demo-data mutating checks.
bash tests/e2e/finix-smoke.sh http://localhost:3000 --admin-url http://localhost:3001

# A deployed environment behind TLS.
bash tests/e2e/finix-smoke.sh https://finix.example.org \
  --admin-url https://admin.finix.example.org --json-report smoke.json

bash tests/e2e/finix-smoke.sh --help
```

`BASE_URL` is the **web** application origin — the nginx that also reverse-proxies
`/api/account`, `/api/orchestrator`, `/api/ledger` and `/api/ussd`. `--admin-url` is the
**admin** origin, which proxies `/api/vault`, `/api/risk`, `/api/compliance`, `/api/loan`,
`/api/notify` and `/api/pay`. Without it, the checks behind those prefixes SKIP rather
than fail — unless the direct ports are reachable (automatic for loopback hosts).

Exit codes: `0` clean · `1` a critical check failed · `2` only non-critical failures ·
`3` usage error · `4` preflight (missing tooling, unreachable base URL).

## The two phases

**READ-ONLY** issues nothing but GET and HEAD: the web and admin apps, every reverse-proxy
route, seeded balances, the ledger hash chain and anchors, the vault ceremony state, and
the collection endpoints.

**DEMO-DATA MUTATING** moves **LKR 1.00** between the seeded farmer and SME accounts and
back: the allow / step-up / blocked risk paths, idempotent replay, double-entry ledger
posting, an independently recomputed Merkle inclusion proof, an offline voucher and its
replay rejection, USSD, loan, compliance, payment-hub and notification demos.

## Safety

* Demo identifiers are parsed from `services/account-service/.../domain/DemoAccounts.kt`
  and `services/ussd-gateway/.../domain/UssdDirectory.kt`, never hard-coded here. Override
  with `--seed-file` or the `FINIX_*_ACCOUNT_ID` environment variables.
* Money-moving checks refuse to run unless the live account number **and** owner match the
  seed configuration, so the harness cannot be aimed at a real customer account.
* Every request carries a connect and total timeout. TLS verification is never disabled —
  `--cacert` adds trust anchors, it does not remove them.
* Tokens, keys and signature material are redacted from all output and from the JSON report.
* No delete, no ledger tamper, no vault ceremony transition. The run ends by transferring
  back exactly what it moved and re-asserting the opening balances, so reruns are safe.

## Validate the harness itself

```bash
bash tests/e2e/selftest.sh
```

66 assertions covering the CLI contract, the helper units (money, Merkle, redaction), that
`--read-only` leaves the mock's state byte-identical, that a full run restores the demo
balances, and — most importantly — that injected faults (broken hash chain, a non-seed
account number, a duplicate that debits twice) actually turn the run red.
