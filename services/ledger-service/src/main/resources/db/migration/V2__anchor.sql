-- M5: Merkle window anchors signed with ML-DSA-65 (ADR-0004).

CREATE TABLE ledger_anchor (
    id              UUID         PRIMARY KEY,
    window_start_seq BIGINT      NOT NULL,
    window_end_seq   BIGINT      NOT NULL,
    merkle_root     CHAR(64)     NOT NULL,
    entry_count     INT          NOT NULL,
    signature       BYTEA        NOT NULL,
    public_key      BYTEA        NOT NULL,
    anchored_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_ledger_anchor_window UNIQUE (window_start_seq, window_end_seq)
);

CREATE INDEX idx_ledger_anchor_anchored_at ON ledger_anchor (anchored_at DESC);
CREATE INDEX idx_ledger_anchor_end_seq ON ledger_anchor (window_end_seq);

-- Dev-only tamper helper: bypasses append-only triggers so the demo can flip one byte.
-- Production roles must not be granted EXECUTE on this function.
CREATE OR REPLACE FUNCTION finix_dev_tamper_entry_hash(p_sequence BIGINT) RETURNS void AS $$
DECLARE
    old_hash CHAR(64);
    new_hash CHAR(64);
BEGIN
    SELECT entry_hash INTO old_hash FROM ledger_entry WHERE sequence = p_sequence;
    IF old_hash IS NULL THEN
        RAISE EXCEPTION 'no ledger entry at sequence %', p_sequence;
    END IF;
    -- Flip the first hex nibble so the chain break is obvious and deterministic.
    new_hash := CASE
        WHEN left(old_hash, 1) = '0' THEN '1' || substr(old_hash, 2)
        ELSE '0' || substr(old_hash, 2)
    END;
    ALTER TABLE ledger_entry DISABLE TRIGGER trg_ledger_entry_immutable;
    UPDATE ledger_entry SET entry_hash = new_hash WHERE sequence = p_sequence;
    ALTER TABLE ledger_entry ENABLE TRIGGER trg_ledger_entry_immutable;
END;
$$ LANGUAGE plpgsql;

COMMENT ON TABLE ledger_anchor IS
    'ML-DSA-65 signed Merkle root over a window of ledger entry hashes (typically 60s).';
COMMENT ON FUNCTION finix_dev_tamper_entry_hash IS
    'DEV ONLY: flips one hex digit of entry_hash for the tamper demo. Not for production.';
