-- Compliance case aggregate for AML / sanctions / fraud / SAR / Travel Rule.

CREATE TABLE compliance_case (
    id            UUID         PRIMARY KEY,
    type          TEXT         NOT NULL,
    subject_ref   TEXT         NOT NULL,
    status        TEXT         NOT NULL,
    severity      TEXT         NOT NULL,
    notes         TEXT         NOT NULL DEFAULT '',
    opened_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at     TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_case_type CHECK (
        type IN ('AML', 'SANCTIONS', 'FRAUD', 'SAR', 'TRAVEL_RULE')
    ),
    CONSTRAINT chk_case_status CHECK (
        status IN ('OPEN', 'INVESTIGATING', 'CLOSED')
    ),
    CONSTRAINT chk_case_severity CHECK (
        severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    )
);

CREATE INDEX idx_compliance_case_status ON compliance_case (status);
CREATE INDEX idx_compliance_case_type ON compliance_case (type);
CREATE INDEX idx_compliance_case_subject ON compliance_case (subject_ref);

COMMENT ON TABLE compliance_case IS 'AML/sanctions/fraud/SAR/Travel Rule investigation cases.';
