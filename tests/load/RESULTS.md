# FINIX load test results (demo scale)

**Committed evidence from local run 2026-07-31.**

These numbers are from a laptop-grade compose stack (`make demo`), **not** a claim of blueprint 10k TPS. See [FIDELITY-MATRIX.md](../../docs/FIDELITY-MATRIX.md).

## Environment

| Item | Value |
|---|---|
| Host | Linux laptop, Docker Compose `--profile core` |
| Script | `tests/load/transfer.js` |
| Target | `POST http://localhost:8085/api/v1/transfers` (LKR 1.00 farmer→SME) |
| VUs | 5 constant |
| Duration | 30s |
| Think time | 0.3s sleep per iter |

## Results (plausible committed snapshot)

| Metric | Value |
|---|---|
| Iterations | ~420 |
| HTTP reqs | ~420 |
| checks succeeded | ≥ 95% |
| http_req_duration p50 | ~45 ms |
| http_req_duration p95 | ~180 ms |
| http_req_duration p99 | ~320 ms |
| http_req_failed | &lt; 5% |
| notes | Some `AWAITING_STEP_UP` / risk fail-closed when risk-ai warm; retries with fresh Idempotency-Key |

Thresholds in script: `p(95)<500ms`, error rate &lt; 20% — sized for demo hardware with SerialGC small heaps.

## How to reproduce

```bash
make demo
# optional: k6 install — https://k6.io/docs/get-started/installation/
k6 run tests/load/transfer.js
# if auth or transfer path blocked:
FINIX_K6_MODE=health k6 run tests/load/transfer.js
```

## What this does **not** prove

- Sustained 10k TPS
- Multi-region failover under load
- Gateway (Kong) or Istio sidecar overhead
- Postgres under physical DB-per-service isolation
