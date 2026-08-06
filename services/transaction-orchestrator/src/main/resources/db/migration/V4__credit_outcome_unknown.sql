-- Marks a transfer whose recipient credit was attempted but whose outcome could not be
-- established (timeout / unreachable account-service). Such a saga must never be compensated:
-- returning the sender's funds could pay out an amount the recipient already holds.
--
-- This is a column rather than a new state value for two reasons: it is a fact about the money
-- that must survive alongside ledger_posted and hold_committed without disturbing the state
-- machine, and an unknown column is ignored by older instances, whereas an unknown *state*
-- string would break SagaState.valueOf during a rolling deploy.
--
-- Backward-safe: NOT NULL DEFAULT FALSE is a metadata-only change on PostgreSQL 11+, existing
-- rows read as FALSE (none of them were frozen for reconciliation), and instances running the
-- previous version neither insert nor update this column, so they cannot clear it.

ALTER TABLE transfer_saga
    ADD COLUMN IF NOT EXISTS credit_outcome_unknown BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN transfer_saga.credit_outcome_unknown IS
    'True when the recipient credit outcome is unresolved; blocks all compensation until reconciled.';
