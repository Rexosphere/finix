-- Offline voucher reconciliation: per-device monotonic seq + nonce set (BIS Polaris).

CREATE TABLE offline_device (
    device_id            TEXT         PRIMARY KEY,
    owner_user_id        UUID         NOT NULL,
    account_id           UUID         NOT NULL REFERENCES account (id),
    public_key_spki      BYTEA        NOT NULL,
    last_device_seq      BIGINT       NOT NULL DEFAULT 0,
    cumulative_minor     BIGINT       NOT NULL DEFAULT 0,
    quarantined          BOOLEAN      NOT NULL DEFAULT FALSE,
    quarantine_reason    TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_offline_last_seq_nonneg CHECK (last_device_seq >= 0),
    CONSTRAINT chk_offline_cumulative_nonneg CHECK (cumulative_minor >= 0)
);

CREATE INDEX idx_offline_device_owner ON offline_device (owner_user_id);
CREATE INDEX idx_offline_device_account ON offline_device (account_id);

CREATE TABLE offline_nonce (
    device_id   TEXT         NOT NULL REFERENCES offline_device (device_id),
    nonce       TEXT         NOT NULL,
    seen_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, nonce)
);

CREATE TABLE offline_voucher (
    id               UUID         PRIMARY KEY,
    device_id        TEXT         NOT NULL REFERENCES offline_device (device_id),
    payer_account_id UUID         NOT NULL,
    payee_account_id UUID         NOT NULL,
    amount_minor     BIGINT       NOT NULL,
    currency         CHAR(3)      NOT NULL DEFAULT 'LKR',
    device_seq       BIGINT       NOT NULL,
    nonce            TEXT         NOT NULL,
    valid_until      TIMESTAMPTZ  NOT NULL,
    status           TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_offline_voucher_status CHECK (status IN ('SETTLED', 'REJECTED')),
    CONSTRAINT chk_offline_voucher_amount_positive CHECK (amount_minor > 0)
);

CREATE UNIQUE INDEX uq_offline_voucher_device_seq ON offline_voucher (device_id, device_seq);
CREATE INDEX idx_offline_voucher_device ON offline_voucher (device_id);

COMMENT ON TABLE offline_device IS 'Registered offline-capable devices with Polaris cumulative counters.';
COMMENT ON TABLE offline_nonce IS 'Consumed voucher nonces — reuse is a double-spend.';
COMMENT ON TABLE offline_voucher IS 'Settled or rejected offline vouchers for audit.';
