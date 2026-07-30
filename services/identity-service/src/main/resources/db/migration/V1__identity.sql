-- Identity aggregate: one profile per Keycloak subject, many devices per profile.
-- Device trust is scored on the hot path at login; KYC tier gates product eligibility.

CREATE TABLE user_profile (
    id                UUID         PRIMARY KEY,
    keycloak_user_id  VARCHAR(64)  NOT NULL,
    email             VARCHAR(320) NOT NULL,
    display_name      VARCHAR(200) NOT NULL,
    nic               VARCHAR(32),
    locale            VARCHAR(16)  NOT NULL DEFAULT 'en',
    kyc_tier          VARCHAR(32)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_user_profile_keycloak ON user_profile (keycloak_user_id);
CREATE UNIQUE INDEX uq_user_profile_email ON user_profile (email);

CREATE TABLE device (
    id            UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES user_profile (id),
    fingerprint   VARCHAR(128) NOT NULL,
    platform      VARCHAR(64)  NOT NULL,
    trust_score   INT          NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL,
    revoked       BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_device_trust_score CHECK (trust_score BETWEEN 0 AND 100)
);

CREATE INDEX idx_device_user_id ON device (user_id);
CREATE INDEX idx_device_user_fingerprint ON device (user_id, fingerprint);

COMMENT ON TABLE user_profile IS
    'FINIX retail/SME identity projection of a Keycloak subject; KYC lives here, credentials do not.';
COMMENT ON TABLE device IS
    'Registered browser/app fingerprints with a trust score used by adaptive login risk.';
