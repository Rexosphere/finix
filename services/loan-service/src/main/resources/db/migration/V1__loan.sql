-- SME micro-loan aggregate with equal-installment repayment schedule.

CREATE TABLE loan (
    id                  UUID         PRIMARY KEY,
    borrower_user_id    UUID         NOT NULL,
    account_id          UUID         NOT NULL,
    principal_minor     BIGINT       NOT NULL,
    currency            CHAR(3)      NOT NULL DEFAULT 'LKR',
    status              TEXT         NOT NULL,
    term_months         INT          NOT NULL,
    credit_score        INT,
    risk_hint           TEXT,
    applied_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at          TIMESTAMPTZ,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_loan_status CHECK (
        status IN ('PENDING', 'APPROVED', 'DISBURSED', 'REPAYING', 'CLOSED', 'REJECTED')
    ),
    CONSTRAINT chk_loan_principal_positive CHECK (principal_minor > 0),
    CONSTRAINT chk_loan_term_positive CHECK (term_months > 0)
);

CREATE INDEX idx_loan_borrower ON loan (borrower_user_id);
CREATE INDEX idx_loan_status ON loan (status);

CREATE TABLE loan_repayment (
    id                   UUID         PRIMARY KEY,
    loan_id              UUID         NOT NULL REFERENCES loan (id) ON DELETE CASCADE,
    installment_number   INT          NOT NULL,
    due_date             DATE         NOT NULL,
    amount_minor         BIGINT       NOT NULL,
    currency             CHAR(3)      NOT NULL DEFAULT 'LKR',
    status               TEXT         NOT NULL DEFAULT 'DUE',
    CONSTRAINT chk_repayment_status CHECK (status IN ('DUE', 'PAID', 'OVERDUE', 'WAIVED')),
    CONSTRAINT chk_repayment_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT uq_loan_installment UNIQUE (loan_id, installment_number)
);

CREATE INDEX idx_loan_repayment_loan ON loan_repayment (loan_id);

COMMENT ON TABLE loan IS 'SME micro-loan applications and lifecycle state.';
COMMENT ON TABLE loan_repayment IS 'Equal-installment repayment schedule owned by the loan aggregate.';
