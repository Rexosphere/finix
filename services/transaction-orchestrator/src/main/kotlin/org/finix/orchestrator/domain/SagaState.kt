package org.finix.orchestrator.domain

/**
 * Lifecycle of an internal-transfer saga.
 *
 * Happy path: [INITIATED] → ([AWAITING_STEP_UP] →) [FUNDS_RESERVED] → … → [COMPLETED].
 * Risk [BLOCKED] is terminal before funds move. Failures after reserve enter compensation.
 */
enum class SagaState {
    INITIATED,
    AWAITING_STEP_UP,
    FUNDS_RESERVED,
    LEDGER_POSTED,
    CREDIT_APPLIED,
    COMPLETED,
    COMPENSATING,
    COMPENSATED,
    BLOCKED,
    FAILED,
}
