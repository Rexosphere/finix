-- Master Key ceremony: sealed shards, custodian approvals, enclave egress log.
-- Plaintext key material is never stored; only hybrid-sealed ciphertexts and public VSS commitments.

CREATE TABLE ceremony (
    id                     UUID         PRIMARY KEY,
    state                  TEXT         NOT NULL,
    threshold              INT          NOT NULL DEFAULT 3,
    commitments            BYTEA        NOT NULL,
    sealed_network_config  BYTEA        NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_ceremony_state CHECK (
        state IN ('PENDING', 'COLLECTING', 'THRESHOLD_MET', 'RECONSTRUCTING', 'UNLOCKED', 'FAILED')
    ),
    CONSTRAINT chk_ceremony_threshold CHECK (threshold >= 2 AND threshold <= 5)
);

CREATE TABLE sealed_shard (
    id            UUID         PRIMARY KEY,
    ceremony_id   UUID         NOT NULL REFERENCES ceremony (id) ON DELETE CASCADE,
    custodian_id  TEXT         NOT NULL,
    share_index   INT          NOT NULL,
    ciphertext    BYTEA        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_sealed_shard_ceremony_custodian UNIQUE (ceremony_id, custodian_id),
    CONSTRAINT uq_sealed_shard_ceremony_index UNIQUE (ceremony_id, share_index),
    CONSTRAINT chk_sealed_shard_custodian CHECK (
        custodian_id IN ('CENTRAL_BANK', 'GOVT_DR', 'IEEE_VAULT', 'CLOUD_HSM_A', 'CLOUD_HSM_B')
    ),
    CONSTRAINT chk_sealed_shard_index CHECK (share_index BETWEEN 1 AND 5)
);

CREATE INDEX idx_sealed_shard_ceremony ON sealed_shard (ceremony_id);

CREATE TABLE custodian_approval (
    id            UUID         PRIMARY KEY,
    ceremony_id   UUID         NOT NULL REFERENCES ceremony (id) ON DELETE CASCADE,
    custodian_id  TEXT         NOT NULL,
    approved_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_approval_ceremony_custodian UNIQUE (ceremony_id, custodian_id),
    CONSTRAINT chk_approval_custodian CHECK (
        custodian_id IN ('CENTRAL_BANK', 'GOVT_DR', 'IEEE_VAULT', 'CLOUD_HSM_A', 'CLOUD_HSM_B')
    )
);

CREATE INDEX idx_approval_ceremony ON custodian_approval (ceremony_id);

CREATE TABLE egress_log (
    id            UUID         PRIMARY KEY,
    ceremony_id   UUID         NOT NULL REFERENCES ceremony (id) ON DELETE CASCADE,
    recorded_at   TIMESTAMPTZ  NOT NULL,
    message       TEXT         NOT NULL
);

CREATE INDEX idx_egress_log_ceremony ON egress_log (ceremony_id, recorded_at);

COMMENT ON TABLE ceremony IS 'Master Key unlock ceremony; commitments are public Feldman VSS points.';
COMMENT ON TABLE sealed_shard IS 'Hybrid-sealed Shamir+Feldman share payloads; plaintext only inside enclave.';
COMMENT ON TABLE custodian_approval IS 'Custodian approvals toward the 3-of-5 threshold.';
COMMENT ON TABLE egress_log IS 'Enclave egress proof: network-config plaintext only, never the Master Key.';
COMMENT ON COLUMN ceremony.commitments IS 'Length-prefixed compressed secp256r1 commitment points.';
