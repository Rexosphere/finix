#!/bin/sh
# Logical database-per-service (ADR-0003). Each role owns exactly one database;
# no cross-database GRANTs. The bootstrap superuser `finix` exists only for init.
#
# Replaces the previous init-databases.sql: role passwords are read from the
# mounted Docker secrets instead of being literals in a tracked file. psql's
# :'var' interpolation quotes them safely.
#
# Runs once, when the pgdata volume is empty (docker-entrypoint-initdb.d).
set -eu

secret() {
  file="/run/secrets/$1"
  if [ ! -s "$file" ]; then
    echo "init-databases: missing secret $file — run ./scripts/gen-secrets.sh" >&2
    exit 1
  fi
  cat "$file"
}

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v identity_pw="$(secret db_identity_password)" \
  -v account_pw="$(secret db_account_password)" \
  -v ledger_pw="$(secret db_ledger_password)" \
  -v orchestrator_pw="$(secret db_orchestrator_password)" \
  -v vault_svc_pw="$(secret db_vault_svc_password)" \
  -v compliance_pw="$(secret db_compliance_password)" \
  -v loan_pw="$(secret db_loan_password)" \
  -v ussd_pw="$(secret db_ussd_password)" <<'EOSQL'
CREATE USER identity WITH PASSWORD :'identity_pw';
CREATE DATABASE identity OWNER identity;

CREATE USER account WITH PASSWORD :'account_pw';
CREATE DATABASE account OWNER account;

CREATE USER ledger WITH PASSWORD :'ledger_pw';
CREATE DATABASE ledger OWNER ledger;

CREATE USER orchestrator WITH PASSWORD :'orchestrator_pw';
CREATE DATABASE orchestrator OWNER orchestrator;

CREATE USER vault_svc WITH PASSWORD :'vault_svc_pw';
CREATE DATABASE vault OWNER vault_svc;

CREATE USER compliance WITH PASSWORD :'compliance_pw';
CREATE DATABASE compliance OWNER compliance;

CREATE USER loan WITH PASSWORD :'loan_pw';
CREATE DATABASE loan OWNER loan;

CREATE USER ussd WITH PASSWORD :'ussd_pw';
CREATE DATABASE ussd OWNER ussd;

-- Deny the bootstrap role from being the default search path for app work.
REVOKE ALL ON DATABASE identity FROM PUBLIC;
REVOKE ALL ON DATABASE account FROM PUBLIC;
REVOKE ALL ON DATABASE ledger FROM PUBLIC;
REVOKE ALL ON DATABASE orchestrator FROM PUBLIC;

GRANT CONNECT ON DATABASE identity TO identity;
GRANT CONNECT ON DATABASE account TO account;
GRANT CONNECT ON DATABASE ledger TO ledger;
GRANT CONNECT ON DATABASE orchestrator TO orchestrator;
GRANT CONNECT ON DATABASE vault TO vault_svc;
GRANT CONNECT ON DATABASE compliance TO compliance;
GRANT CONNECT ON DATABASE loan TO loan;
GRANT CONNECT ON DATABASE ussd TO ussd;
EOSQL
