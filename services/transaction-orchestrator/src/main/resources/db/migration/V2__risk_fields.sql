-- M7: risk metadata on transfer sagas (score + decision).

ALTER TABLE transfer_saga
    ADD COLUMN IF NOT EXISTS risk_score INTEGER,
    ADD COLUMN IF NOT EXISTS risk_decision TEXT;

COMMENT ON COLUMN transfer_saga.risk_score IS 'Blended risk score 0..100 from risk-ai-service';
COMMENT ON COLUMN transfer_saga.risk_decision IS 'allow | step_up | block';
