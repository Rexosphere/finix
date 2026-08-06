#!/usr/bin/env bash
# Restore a FINIX Postgres backup produced by the nightly finix-backup timer.
#
#   scripts/restore-db.sh /opt/finix/backups/finix-20260806T030000Z.sql.gz
#   scripts/restore-db.sh --latest
#
# Drill this, do not discover it: docs/runbooks/dr-failover.md walks the full
# restore, and a backup nobody has restored is a hypothesis, not a backup.
set -euo pipefail

BACKUP_DIR="${FINIX_BACKUP_DIR:-/opt/finix/backups}"
CONTAINER="${FINIX_PG_CONTAINER:-finix-postgres-1}"

usage() {
  echo "usage: $0 <dump.sql.gz> | --latest" >&2
  exit 2
}

[ $# -eq 1 ] || usage

if [ "$1" = "--latest" ]; then
  dump=$(ls -1t "$BACKUP_DIR"/finix-*.sql.gz 2>/dev/null | head -n 1 || true)
  [ -n "$dump" ] || { echo "No backups in $BACKUP_DIR" >&2; exit 1; }
else
  dump="$1"
fi

[ -s "$dump" ] || { echo "Not a readable backup: $dump" >&2; exit 1; }

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "Postgres container $CONTAINER is not running" >&2
  exit 1
fi

cat <<EOF
About to restore into container $CONTAINER:

  $dump  ($(du -h "$dump" | cut -f1))

pg_dumpall output contains DROP/CREATE for every role and database, so this
overwrites current data. Stop the application services first:

  docker compose -f infra/compose/docker-compose.yml --profile full stop \\
    identity-service account-service ledger-service transaction-orchestrator \\
    vault-service loan-service compliance-service

EOF
read -r -p "Type RESTORE to continue: " confirm
[ "$confirm" = "RESTORE" ] || { echo "Aborted."; exit 1; }

gunzip -c "$dump" | docker exec -i "$CONTAINER" sh -c \
  'PGPASSWORD=$(cat /run/secrets/postgres_superuser_password) psql -U finix -d postgres -v ON_ERROR_STOP=1'

echo "Restore complete. Start the services again and re-run tests/e2e/smoke.sh."
