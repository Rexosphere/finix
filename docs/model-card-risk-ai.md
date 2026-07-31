# Risk AI model card — FINIX M7

## Model details

| Field | Value |
|---|---|
| Name | FINIX transaction risk blender |
| Version | 0.1.0 |
| Owners | Team Rexosphere |
| Algorithms | IsolationForest (scikit-learn) + deterministic rules engine |
| Intended use | Score internal transfers; drive saga allow / step-up / block |
| Out of scope | Production credit underwriting, biometric matching |

## Decision policy

| Score | Decision | Saga effect |
|---|---|---|
| 0–39 | `allow` | Continue transfer |
| 40–70 | `step_up` | Suspend in `AWAITING_STEP_UP` until MFA |
| 71–100 | `block` | Terminal `BLOCKED`; open fraud case |

Blend: `0.65 * rules + 0.35 * model` (rules dominate for explainability).

## Features

`amount_z`, `velocity_1h`, `new_device`, `geo_velocity`, `payee_novelty`, `hour_sin`, `hour_cos`, `offline_voucher`.

## Training data

Synthetic, seeded (`SEED=42`) via `scripts/train_model.py`. ~2000 normal + ~5% injected anomalies. Fully reproducible; no customer data.

## Federated learning

`GET /v1/federated/demo` runs a two-bank FedAvg toy — documented as a demonstrator only (fidelity matrix: not production FL).

## AI Shield

`/v1/shield/*` ingests latency/error samples and quarantines services above thresholds (default 800 ms or 25% errors). Demo can `POST /v1/shield/quarantine`.

## Limitations

- Cold-start mean/std for amount z-score are constants, not per-user history.
- Login IP reputation is a demo prefix rule, not a commercial feed.
- Cases are in-memory until M8 compliance-service persists them.
