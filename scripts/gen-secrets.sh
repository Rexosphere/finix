#!/usr/bin/env bash
# Generate the credential files that compose mounts as Docker secrets.
#
# Nothing in this repository contains a real credential: every password the
# stack needs is generated here, on the machine that runs it, into
# infra/compose/secrets/ (gitignored). The compose file references the files,
# never the values.
#
#   ./scripts/gen-secrets.sh          create any missing secret (idempotent)
#   ./scripts/gen-secrets.sh --show   print the human-facing logins
#   ./scripts/gen-secrets.sh --force  regenerate everything (see warning below)
#
# WARNING on --force: Postgres roles are created from these files exactly once,
# when the pgdata volume is initialised. Regenerating afterwards leaves the
# services holding passwords the database no longer accepts. Run `make down`
# (which drops volumes) before --force.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/infra/compose/secrets"

# Every secret the compose stack mounts. Postgres role passwords are named
# db_<role>_password so init-databases.sh can derive the file from the role.
SECRETS=(
  postgres_superuser_password
  db_identity_password
  db_account_password
  db_ledger_password
  db_orchestrator_password
  db_vault_svc_password
  db_compliance_password
  db_loan_password
  db_ussd_password
  keycloak_admin_password
  grafana_admin_password
  vault_root_token
)

force=0
show=0
for arg in "$@"; do
  case "$arg" in
    --force) force=1 ;;
    --show) show=1 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

gen() {
  # Hex only: these values end up in JDBC URLs, psql literals and shell
  # exports, and hex needs no escaping in any of them.
  #
  # No trailing newline: consumers differ on whether they strip one (Spring's
  # configtree does, Grafana's __FILE provider and postgres_exporter make no
  # such promise), and a stray \n inside a password is silent auth failure.
  if command -v openssl >/dev/null 2>&1; then
    printf '%s' "$(openssl rand -hex 24)"
  else
    head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

mkdir -p "$DIR"
chmod 700 "$DIR"

created=0
for name in "${SECRETS[@]}"; do
  file="$DIR/$name"
  if [ "$force" -eq 1 ] || [ ! -s "$file" ]; then
    umask 077
    gen > "$file"
    # World-readable inside the 0700 directory: containers run as assorted
    # uids (postgres, keycloak, grafana, our own 10001) and each must be able
    # to read its own secret. The directory mode is what keeps other host
    # users out.
    chmod 444 "$file"
    created=$((created + 1))
  fi
done

if [ "$created" -gt 0 ]; then
  echo "==> Generated $created secret(s) in infra/compose/secrets/"
elif [ "$show" -eq 0 ]; then
  echo "==> Secrets already present in infra/compose/secrets/ (nothing to do)"
fi

if [ "$show" -eq 1 ] || [ "$created" -gt 0 ]; then
  cat <<EOF

Console logins for this machine:
  Keycloak admin   admin / $(cat "$DIR/keycloak_admin_password")
  Grafana admin    admin / $(cat "$DIR/grafana_admin_password")
  Vault dev token  $(cat "$DIR/vault_root_token")

Re-read them any time with: ./scripts/gen-secrets.sh --show
EOF
fi
