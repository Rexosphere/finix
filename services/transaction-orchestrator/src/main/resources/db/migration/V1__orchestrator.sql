-- Transfer saga state machine for internal transfers (M3).
-- Kernel outbox ships separately via classpath:db/kernel.

CREATE TABLE transfer_saga (
    id               UUID         PRIMARY KEY,
    from_account_id  UUID         NOT NULL,
    to_account_id    UUID         NOT NULL,
    amount_minor     BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency         CHAR(3)      NOT NULL,
    state            TEXT         NOT NULL,
    hold_id          UUID         NOT NULL,
    ledger_posted    BOOLEAN      NOT NULL DEFAULT FALSE,
    failure_reason   TEXT,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_transfer_saga_state ON transfer_saga (state);
CREATE INDEX idx_transfer_saga_created_at ON transfer_saga (created_at);

COMMENT ON TABLE transfer_saga IS
    'Saga orchestrator state for internal transfers; id doubles as ledger transactionId.';
