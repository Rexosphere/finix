#!/usr/bin/env bash
# Soft health smoke for FINIX compose services.
# Usage:
#   bash tests/e2e/smoke.sh
#   FINIX_SMOKE_STRICT=1 bash tests/e2e/smoke.sh   # any down → exit 1
set -uo pipefail

STRICT="${FINIX_SMOKE_STRICT:-0}"
FAILS=0
OKS=0
SKIPS=0

check() {
  local name="$1" url="$2"
  if curl -sf --connect-timeout 2 --max-time 5 "$url" >/dev/null 2>&1; then
    printf '  OK    %-22s %s\n' "$name" "$url"
    OKS=$((OKS + 1))
  else
    if [[ "$STRICT" == "1" ]]; then
      printf '  FAIL  %-22s %s\n' "$name" "$url"
      FAILS=$((FAILS + 1))
    else
      printf '  SKIP  %-22s %s (down — soft fail)\n' "$name" "$url"
      SKIPS=$((SKIPS + 1))
    fi
  fi
}

echo "==> FINIX e2e smoke (health)"
echo "    STRICT=$STRICT"

# JVM Spring Boot
check "identity"       "http://localhost:8082/actuator/health"
check "account"        "http://localhost:8083/actuator/health"
check "ledger"         "http://localhost:8084/actuator/health"
check "orchestrator"   "http://localhost:8085/actuator/health"
check "vault"          "http://localhost:8086/actuator/health"
check "ussd"           "http://localhost:8087/actuator/health"
check "loan"           "http://localhost:8088/actuator/health"
check "compliance"     "http://localhost:8089/actuator/health"
check "enclave"        "http://localhost:8090/actuator/health"

# Polyglot / static
check "risk-ai"        "http://localhost:8091/health"
check "payment-hub"    "http://localhost:8092/health"
check "notification"   "http://localhost:8093/health"
check "web"            "http://localhost:3000/"
check "admin"          "http://localhost:3001/"
check "keycloak"       "http://localhost:8081/realms/finix"

echo
echo "Summary: ok=$OKS skip=$SKIPS fail=$FAILS"

if [[ "$STRICT" == "1" && "$FAILS" -gt 0 ]]; then
  echo "STRICT mode: failing because $FAILS endpoint(s) were down"
  exit 1
fi

if [[ "$OKS" -eq 0 ]]; then
  echo "No services responded. Start the stack with: make demo"
  exit 1
fi

echo "Soft smoke complete."
exit 0
