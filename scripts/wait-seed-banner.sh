#!/usr/bin/env bash
# Shared tail for make demo / make demo-pull: wait healthy, seed, print URLs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose -f infra/compose/docker-compose.yml --profile core)
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-finix}"

echo "==> Waiting for health"
for svc in identity-service account-service ledger-service transaction-orchestrator; do
  port=$(case $svc in
    identity-service) echo 8082 ;;
    account-service) echo 8083 ;;
    ledger-service) echo 8084 ;;
    transaction-orchestrator) echo 8085 ;;
  esac)
  echo -n "  $svc "
  for i in $(seq 1 60); do
    if curl -sf "http://localhost:${port}/actuator/health" >/dev/null 2>&1; then
      echo "ok"
      break
    fi
    if [ "$i" -eq 60 ]; then
      echo "TIMEOUT"
      "${COMPOSE[@]}" logs --tail=80 "$svc" || true
      exit 1
    fi
    echo -n "."
    sleep 2
  done
done

./scripts/seed.sh

echo
echo "FINIX is up."
echo
# Generated per machine by scripts/gen-secrets.sh — printed here so the demo is
# still one command, but never stored in the repository.
printf '  Keycloak admin   http://localhost:8081  (admin / %s)\n' \
  "$(cat infra/compose/secrets/keycloak_admin_password)"

cat <<'EOF'
  Identity         http://localhost:8082
  Account          http://localhost:8083
  Ledger           http://localhost:8084
  Orchestrator     http://localhost:8085
  Vault            http://localhost:8086
  USSD gateway     http://localhost:8087
  Loan             http://localhost:8088
  Compliance       http://localhost:8089
  Enclave          http://localhost:8090
  Risk AI          http://localhost:8091
  Payment Hub      http://localhost:8092
  Notifications    http://localhost:8093
  Web PWA / lite   http://localhost:3000
  Admin ceremony   http://localhost:3001
  Redpanda Kafka   localhost:19092

Demo users (password: Finix!2026 for all):
  farmer@finix.lk      rural farmer persona
  sme@finix.lk         Moratuwa SME
  elder@finix.lk       Sinhala-speaking elder
  regulator@finix.lk   regulator / audit

Seeded accounts:
  farmer  a2222222-2222-4222-8222-222222222201  FINIX-SAV-00000001
  sme     a2222222-2222-4222-8222-222222222202  FINIX-CUR-00000002
  elder   a2222222-2222-4222-8222-222222222203  FINIX-SAV-00000003

Docs: docs/USER-GUIDE.md · docs/DEMO.md · docs/FIDELITY-MATRIX.md
Smoke: bash tests/e2e/smoke.sh

Internal transfer:
  curl -X POST http://localhost:8085/api/v1/transfers \
    -H 'Content-Type: application/json' \
    -H 'Idempotency-Key: demo-1' \
    -d '{"fromAccountId":"a2222222-2222-4222-8222-222222222201","toAccountId":"a2222222-2222-4222-8222-222222222202","amount":"LKR 100.00"}'
EOF
