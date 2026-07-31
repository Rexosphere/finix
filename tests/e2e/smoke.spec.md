# Playwright journeys (planned)

These are the headed journeys that should eventually automate [docs/DEMO.md](../../docs/DEMO.md). **Not required for M9 bash smoke.** Record video artifacts when the suite lands.

Base URL: `http://localhost:3000` (web), `http://localhost:3001` (admin). Stack must be up via `make demo`.

## Journey A — Money + integrity

1. Open `/` (or transfer UI if present); otherwise drive API from a setup fixture.
2. Create internal transfer farmer → SME (LKR 100).
3. Assert orchestrator state `COMPLETED`.
4. Open `/verify.html`, load chain / proof for the tx id.
5. Assert UI shows valid chain (or API fixture `valid: true`).

## Journey B — Risk step-up

1. POST transfer with `newDevice: true` (fixture helper).
2. Assert `AWAITING_STEP_UP`.
3. Complete step-up with OTP `123456`.
4. Assert `COMPLETED`.

## Journey C — Offline double-spend

1. Register offline device / create voucher in PWA.
2. Reconcile once → accepted.
3. Reconcile duplicate nonce → rejected; device quarantined flag visible or API assert.

## Journey D — USSD simulator

1. Open `/ussd.html`.
2. Session as `+94771110001`, empty text → welcome `CON` menu.
3. Send `1` → balance `END` containing available amount.

## Journey E — Vault ceremony (closer)

1. Open `http://localhost:3001`.
2. Seed ceremony.
3. Approve three custodians.
4. Reconstruct → banner visible; egress log non-empty and without raw master key material.

## Journey F — Lite budget companion

1. `GET /lite.html` size &lt; 50 KB (also enforced by `scripts/check-lite-budget.sh`).

---

### Suggested Playwright layout (future)

```
tests/e2e/
  smoke.spec.md      ← this file
  smoke.sh           ← health curls (ships now)
  playwright.config.ts
  specs/
    transfer.spec.ts
    verify.spec.ts
    ussd.spec.ts
    ceremony.spec.ts
```

Until those specs exist, graders use `bash tests/e2e/smoke.sh` plus the manual DEMO.md path.
