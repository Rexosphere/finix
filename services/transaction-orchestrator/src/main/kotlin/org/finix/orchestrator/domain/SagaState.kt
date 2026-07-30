package org.finix.orchestrator.domain

/**
 * Lifecycle of an internal-transfer saga.
 *
 * Happy path advances left-to-right through [INITIATED] → … → [COMPLETED].
 * Any failure after funds are reserved enters [COMPENSATING] and ends in
 * [COMPENSATED] (or [FAILED] if compensation itself cannot finish).
 */
enum class SagaState {
    INITIATED,
    FUNDS_RESERVED,
    LEDGER_POSTED,
    CREDIT_APPLIED,
    COMPLETED,
    COMPENSATING,
    COMPENSATED,
    FAILED,
}
