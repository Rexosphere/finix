-- Append-only double-entry journal. Mutations are refused by BEFORE UPDATE/DELETE triggers
-- so a compromised application credential still cannot rewrite history. Table-owner privileges
-- still include UPDATE/DELETE in Postgres; the TRIGGER is the demo-visible enforcement
-- (psql UPDATE … → "ledger is append-only").

CREATE TABLE ledger_entry (
    id             UUID         PRIMARY KEY,
    transaction_id UUID         NOT NULL,
    sequence       BIGINT       NOT NULL,
    prev_hash      CHAR(64)     NOT NULL,
    entry_hash     CHAR(64)     NOT NULL,
    payload        JSONB        NOT NULL,
    recorded_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_ledger_entry_sequence UNIQUE (sequence),
    CONSTRAINT uq_ledger_entry_hash UNIQUE (entry_hash),
    CONSTRAINT uq_ledger_entry_transaction UNIQUE (transaction_id)
);

CREATE INDEX idx_ledger_entry_sequence ON ledger_entry (sequence);

CREATE TABLE ledger_line (
    id           UUID        PRIMARY KEY,
    entry_id     UUID        NOT NULL REFERENCES ledger_entry (id),
    account_id   UUID        NOT NULL,
    side         TEXT        NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_minor BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency     CHAR(3)     NOT NULL
);

CREATE INDEX idx_ledger_line_entry ON ledger_line (entry_id);
CREATE INDEX idx_ledger_line_account ON ledger_line (account_id);

-- ---------------------------------------------------------------------------
-- Append-only enforcement
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is forbidden', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

CREATE TRIGGER trg_ledger_line_immutable
    BEFORE UPDATE OR DELETE ON ledger_line
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

-- Balanced-journal check is enforced in the application (JournalEntry.requireBalanced).
-- Stub kept so a deferred constraint trigger can be added without a schema rename later.
CREATE OR REPLACE FUNCTION check_journal_balanced() RETURNS trigger AS $$
DECLARE
    debit_sum  BIGINT;
    credit_sum BIGINT;
BEGIN
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON TABLE ledger_entry IS
    'Immutable hash-chained journal header. UPDATE/DELETE raise via trg_ledger_entry_immutable.';
COMMENT ON TABLE ledger_line IS
    'Journal lines for a ledger_entry. Append-only; never mutate after insert.';
COMMENT ON COLUMN ledger_entry.payload IS
    'Canonical map that was hashed into entry_hash (RFC 8785 JCS bytes + Hashing.chain).';
COMMENT ON COLUMN ledger_entry.prev_hash IS
    'SHA-256 hex of the previous entry, or Hashing.ZERO_DIGEST for sequence 1.';
