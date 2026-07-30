-- Account aggregate: available vs held balances, optimistic locking via version.

CREATE TABLE account (
    id               UUID         PRIMARY KEY,
    owner_user_id    UUID         NOT NULL,
    account_number   TEXT         NOT NULL,
    type             TEXT         NOT NULL,
    status           TEXT         NOT NULL,
    currency         CHAR(3)      NOT NULL DEFAULT 'LKR',
    available_minor  BIGINT       NOT NULL,
    held_minor       BIGINT       NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_account_type CHECK (type IN ('SAVINGS', 'CURRENT', 'WALLET')),
    CONSTRAINT chk_account_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT chk_account_available_nonneg CHECK (available_minor >= 0),
    CONSTRAINT chk_account_held_nonneg CHECK (held_minor >= 0)
);

CREATE UNIQUE INDEX uq_account_number ON account (account_number);
CREATE INDEX idx_account_owner ON account (owner_user_id);

-- Holds are part of the account aggregate; status tracks the reservation lifecycle.
CREATE TABLE account_hold (
    id            UUID         PRIMARY KEY,
    account_id    UUID         NOT NULL REFERENCES account (id),
    amount_minor  BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status        TEXT         NOT NULL,
    CONSTRAINT chk_hold_status CHECK (status IN ('OPEN', 'COMMITTED', 'RELEASED')),
    CONSTRAINT chk_hold_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX idx_account_hold_account ON account_hold (account_id);
CREATE INDEX idx_account_hold_open ON account_hold (account_id) WHERE status = 'OPEN';

COMMENT ON TABLE account IS 'Customer deposit accounts with available/held balances.';
COMMENT ON TABLE account_hold IS 'Funds reservations (OPEN) and their terminal outcomes.';
COMMENT ON COLUMN account.version IS 'Optimistic lock; bumped on every balance-affecting write.';
