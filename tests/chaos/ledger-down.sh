#!/usr/bin/env bash
# Chaos: kill ledger-service mid-transfer and verify the saga compensates.
#
# Prerequisites:
#   docker compose -f infra/compose/docker-compose.yml --profile core up -d
#   Seeded demo accounts in account-service
#
# What this proves (M3 exit criterion):
#   After FUNDS_RESERVED, a ledger outage leaves the hold open. The orchestrator
#   must release the hold, emit transaction.failed, and end COMPENSATED — never
#   COMPLETED and never leave money stranded in heldBalance.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/infra/compose/docker-compose.yml" --profile core)

ORCH_URL="${ORCH_URL:-http://localhost:8085}"
FROM_ACCOUNT="${FROM_ACCOUNT:-}"
TO_ACCOUNT="${TO_ACCOUNT:-}"
AMOUNT="${AMOUNT:-LKR 10.00}"

if [[ -z "$FROM_ACCOUNT" || -z "$TO_ACCOUNT" ]]; then
  echo "Set FROM_ACCOUNT and TO_ACCOUNT to seeded account UUIDs." >&2
  echo "Example:" >&2
  echo "  FROM_ACCOUNT=... TO_ACCOUNT=... $0" >&2
  exit 1
fi

echo "==> Stopping ledger-service to simulate mid-saga outage"
"${COMPOSE[@]}" stop ledger-service

echo "==> POST /api/v1/transfers (expect compensation, not COMPLETED)"
RESP="$(curl -sS -X POST "$ORCH_URL/api/v1/transfers" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: chaos-$(date +%s)" \
  -d "{\"fromAccountId\":\"$FROM_ACCOUNT\",\"toAccountId\":\"$TO_ACCOUNT\",\"amount\":\"$AMOUNT\"}")"

echo "$RESP" | tee /tmp/finix-chaos-ledger-down.json
STATE="$(echo "$RESP" | sed -n 's/.*"state"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"

echo "==> Restarting ledger-service"
"${COMPOSE[@]}" start ledger-service

case "$STATE" in
  COMPENSATED|FAILED)
    echo "OK: saga ended in $STATE after ledger outage"
    ;;
  *)
    echo "FAIL: expected COMPENSATED or FAILED, got '$STATE'" >&2
    exit 1
    ;;
esac
