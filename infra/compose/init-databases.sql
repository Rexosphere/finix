-- Logical database-per-service (ADR-0003). Each role owns exactly one database;
-- no cross-database GRANTs. The bootstrap superuser `finix` exists only for init.

CREATE USER identity WITH PASSWORD 'identity';
CREATE DATABASE identity OWNER identity;

CREATE USER account WITH PASSWORD 'account';
CREATE DATABASE account OWNER account;

CREATE USER ledger WITH PASSWORD 'ledger';
CREATE DATABASE ledger OWNER ledger;

CREATE USER orchestrator WITH PASSWORD 'orchestrator';
CREATE DATABASE orchestrator OWNER orchestrator;

CREATE USER vault_svc WITH PASSWORD 'vault_svc';
CREATE DATABASE vault OWNER vault_svc;

CREATE USER compliance WITH PASSWORD 'compliance';
CREATE DATABASE compliance OWNER compliance;

CREATE USER loan WITH PASSWORD 'loan';
CREATE DATABASE loan OWNER loan;

CREATE USER ussd WITH PASSWORD 'ussd';
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
