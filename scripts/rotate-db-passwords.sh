#!/usr/bin/env bash
# Align the Postgres roles with the generated secret files.
#
#   ./scripts/rotate-db-passwords.sh
#
# Two situations need this:
#
# 1. **Migrating an existing deployment to ADR-0006.** Role passwords used to be
#    literals in docker-compose.yml (`identity`, `account`, …) and were baked
#    into the pgdata volume when it was first initialised. init-databases.sh only
#    runs on an *empty* volume, so an existing database keeps the old passwords
#    while the services start reading new generated ones — every service then
#    fails to authenticate. This ALTERs the roles to match, without dropping data.
#
# 2. **Rotation.** Regenerate a secret, run this, restart the services.
#
# Safe to re-run: setting a role's password to the value it already has is a
# no-op.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SECRETS="$ROOT/infra/compose/secrets"
CONTAINER="${FINIX_PG_CONTAINER:-finix-postgres-1}"

# role:secret-file
#
# The superuser is included: POSTGRES_PASSWORD_FILE only takes effect when the
# volume is initialised, so on an existing database the superuser keeps whatever
# password it was created with while postgres-exporter and backup-db.sh start
# reading the generated one. psql below connects over the container's local
# socket, which the official image trusts, so this works even when the current
# password is unknown.
ROLES="
finix:postgres_superuser_password
identity:db_identity_password
account:db_account_password
ledger:db_ledger_password
orchestrator:db_orchestrator_password
vault_svc:db_vault_svc_password
compliance:db_compliance_password
loan:db_loan_password
ussd:db_ussd_password
"

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "Postgres container $CONTAINER is not running" >&2
  exit 1
fi

for entry in $ROLES; do
  role="${entry%%:*}"
  file="$SECRETS/${entry##*:}"
  if [ ! -s "$file" ]; then
    echo "Missing $file — run ./scripts/gen-secrets.sh first" >&2
    exit 1
  fi

  # The password travels as an environment variable into the container and then
  # as a psql variable quoted by :'pw'. It never appears on a command line, so it
  # cannot be read out of `ps`.
  docker exec -i -e ROLE="$role" -e PW="$(cat "$file")" "$CONTAINER" sh -s <<'INNER'
set -eu
PGPASSWORD=$(cat /run/secrets/postgres_superuser_password) \
psql -U finix -d postgres -v ON_ERROR_STOP=1 -q -v role="$ROLE" -v pw="$PW" <<'SQL'
ALTER USER :"role" WITH PASSWORD :'pw';
SQL
INNER
  echo "  rotated $role"
done

cat <<'EOF'

Roles now match infra/compose/secrets/. Restart the services so they pick up the
new values:

  docker compose -f infra/compose/docker-compose.yml --profile core up -d --force-recreate \
    identity-service account-service ledger-service transaction-orchestrator \
    vault-service loan-service compliance-service
EOF
