#!/usr/bin/env bash
# make demo — build, start core stack, wait healthy, seed, print URLs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose -f infra/compose/docker-compose.yml --profile core)
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-finix}"

echo "==> Building JVM services"
./gradlew :identity-service:bootJar :account-service:bootJar :ledger-service:bootJar :transaction-orchestrator:bootJar -q

echo "==> Starting compose (core)"
"${COMPOSE[@]}" up -d --build

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

cat <<'EOF'

FINIX is up.

  Keycloak admin   http://localhost:8081  (admin / admin)
  Identity         http://localhost:8082
  Account          http://localhost:8083
  Ledger           http://localhost:8084
  Orchestrator     http://localhost:8085
  Redpanda Kafka   localhost:19092

Demo users (password: Finix!2026 for all):
  farmer@finix.lk      rural farmer persona
  sme@finix.lk         Moratuwa SME
  elder@finix.lk       Sinhala-speaking elder
  regulator@finix.lk   regulator / audit

Internal transfer:
  curl -X POST http://localhost:8085/api/v1/transfers \
    -H 'Content-Type: application/json' \
    -H 'Idempotency-Key: demo-1' \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"fromAccountId":"...","toAccountId":"...","amount":"LKR 100.00"}'
EOF
