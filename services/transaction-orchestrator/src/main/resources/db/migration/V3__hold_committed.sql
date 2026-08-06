-- Records that the sender's hold was committed, alongside the existing ledger_posted fact.
-- Compensation needs it to survive a restart: a committed hold can no longer be released, so a
-- saga that reloads without this flag would pick an invalid compensation and strand the debit.

ALTER TABLE transfer_saga
    ADD COLUMN IF NOT EXISTS hold_committed BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN transfer_saga.hold_committed IS
    'True once the sender hold was committed; compensation must then refund rather than release.';
