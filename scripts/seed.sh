#!/usr/bin/env bash
# Seed blueprint personas into running services.
set -euo pipefail

ACCOUNT_URL="${ACCOUNT_URL:-http://localhost:8083}"
IDENTITY_URL="${IDENTITY_URL:-http://localhost:8082}"

echo "==> Seeding accounts via account-service (dev seed endpoint)"
curl -sf -X POST "${ACCOUNT_URL}/api/v1/admin/seed" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: seed-accounts' \
  || echo "(seed skipped — service may require auth; personas live in Keycloak realm import)"

curl -sf -X POST "${IDENTITY_URL}/api/v1/admin/seed" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: seed-identity' \
  || echo "(identity seed skipped)"

echo "==> Seed complete"
