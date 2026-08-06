-- Two facts that decide whether a committed debit may be financially reversed.
--
-- credit_refused: the recipient credit was *definitively* refused by account-service, which is
-- the only durable proof that the money did not move. A saga at LEDGER_POSTED with a committed
-- hold and without this fact is ambiguous — the credit may have landed and simply not been
-- recorded (a lost process, or a failed CREDIT_APPLIED write) — and must not be compensated.
--
-- refund_attempted: a compensating credit to the sender has been attempted. CreditAccountUseCase
-- ignores its reference, so the credit is not idempotent and a replay would pay the sender twice.
-- The flag is written *before* the call, so a crash on either side of it is safe: the cost is a
-- manual reconciliation, never a duplicated payment.
--
-- Backward-safe on the same terms as V3/V4: NOT NULL DEFAULT FALSE is metadata-only on
-- PostgreSQL 11+, and existing rows read as FALSE. FALSE is the conservative value for both —
-- for credit_refused it withholds permission to reverse, and for refund_attempted no legacy row
-- ever issued a refund, because the previous version compensated by releasing the hold.

ALTER TABLE transfer_saga
    ADD COLUMN IF NOT EXISTS credit_refused   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS refund_attempted BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN transfer_saga.credit_refused IS
    'True once the recipient credit was definitively refused; the only proof that permits reversal.';
COMMENT ON COLUMN transfer_saga.refund_attempted IS
    'True once a compensating refund was attempted; blocks any second, non-idempotent attempt.';
