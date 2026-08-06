package org.finix.orchestrator.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.lkr
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class TransferSagaTest {

    private val from = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val to = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val at = Instant.parse("2026-07-30T12:00:00Z")

    @Test
    fun `happy-path transitions reach COMPLETED`() {
        val saga = TransferSaga.initiate(from, to, "100.00".lkr(), at)
            .markReserved(at)
            .markLedgerPosted(at)
            .markCreditApplied(at)
            .markCompleted(at)

        saga.state shouldBe SagaState.COMPLETED
        saga.failureReason shouldBe null
    }

    @Test
    fun `illegal transition raises Conflict`() {
        val saga = TransferSaga.initiate(from, to, 50.lkr(), at)
        val ex = shouldThrow<DomainException> { saga.markLedgerPosted(at) }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }

    @Test
    fun `compensation path reaches COMPENSATED`() {
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .beginCompensate("ledger down", at)
            .markCompensated(at)

        saga.state shouldBe SagaState.COMPENSATED
        saga.failureReason shouldBe "ledger down"
    }

    @Test
    fun `reserve failure marks FAILED from INITIATED`() {
        val saga = TransferSaga.initiate(from, to, 10.lkr(), at)
            .markFailed("insufficient funds", at)

        saga.state shouldBe SagaState.FAILED
        saga.failureReason shouldBe "insufficient funds"
    }

    @Test
    fun `cannot begin compensate from INITIATED`() {
        val saga = TransferSaga.initiate(from, to, 10.lkr(), at)
        val ex = shouldThrow<DomainException> { saga.beginCompensate("x", at) }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }

    @Test
    fun `markLedgerPosted latches ledgerPosted for compensation replay`() {
        val reserved = TransferSaga.initiate(from, to, 25.lkr(), at).markReserved(at)
        reserved.ledgerPosted shouldBe false

        val posted = reserved.markLedgerPosted(at)
        posted.ledgerPosted shouldBe true

        val compensating = posted.beginCompensate("credit failed", at)
        compensating.state shouldBe SagaState.COMPENSATING
        compensating.ledgerPosted shouldBe true
    }

    @Test
    fun `same account transfer is Invalid`() {
        val ex = shouldThrow<DomainException> {
            TransferSaga.initiate(from, from, 10.lkr(), at)
        }
        ex.error.shouldBeInstanceOf<DomainError.Invalid>()
    }

    @Test
    fun `non-positive amount is Invalid`() {
        val ex = shouldThrow<DomainException> {
            TransferSaga.initiate(from, to, 0.lkr(), at)
        }
        ex.error.shouldBeInstanceOf<DomainError.Invalid>()
    }

    @Test
    fun `cannot mark failed from FUNDS_RESERVED`() {
        val saga = TransferSaga.initiate(from, to, 10.lkr(), at).markReserved(at)
        val ex = shouldThrow<DomainException> { saga.markFailed("x", at) }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }

    @Test
    fun `ledger-posted can begin compensate and keeps the posted flag`() {
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .markLedgerPosted(at)
            .beginCompensate("downstream timeout", at)
        saga.state shouldBe SagaState.COMPENSATING
        saga.ledgerPosted shouldBe true
    }

    @Test
    fun `credit-applied cannot begin compensate because the recipient already has the money`() {
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .markLedgerPosted(at)
            .markCreditApplied(at)
        val ex = shouldThrow<DomainException> { saga.beginCompensate("downstream timeout", at) }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }

    @Test
    fun `committing the hold records the final debit without changing state`() {
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .markLedgerPosted(at)
            .markHoldCommitted(at)
        saga.state shouldBe SagaState.LEDGER_POSTED
        saga.holdCommitted shouldBe true
        // The fact has to outlive the transition into compensation, like ledgerPosted does.
        saga.beginCompensate("credit refused", at).holdCommitted shouldBe true
    }

    @Test
    fun `markHoldCommitted rejects a saga that has not posted its journal`() {
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at).markReserved(at)
        val ex = shouldThrow<DomainException> { saga.markHoldCommitted(at) }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }

    @Test
    fun `an unknown credit outcome is recorded as an explicit flag, not a reason string`() {
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .markLedgerPosted(at)
            .markHoldCommitted(at)
            .markCreditOutcomeUnknown("account-service is unreachable (credit outcome unknown)", at)

        saga.creditOutcomeUnknown shouldBe true
        saga.state shouldBe SagaState.LEDGER_POSTED
        saga.holdCommitted shouldBe true
        saga.failureReason shouldBe "account-service is unreachable (credit outcome unknown)"
    }

    @Test
    fun `markCreditOutcomeUnknown rejects a saga that never reached the credit step`() {
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at).markReserved(at)
        val ex = shouldThrow<DomainException> { saga.markCreditOutcomeUnknown("x", at) }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }

    @Test
    fun `markCreditOutcomeUnknown rejects a saga whose hold is still open`() {
        // Freezing here would block releaseHold, which is the correct idempotent way to refund.
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .markLedgerPosted(at)
        saga.holdCommitted shouldBe false
        val ex = shouldThrow<DomainException> { saga.markCreditOutcomeUnknown("x", at) }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }

    @Test
    fun `an unknown refund outcome can be recorded while compensating`() {
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .markLedgerPosted(at)
            .markHoldCommitted(at)
            .beginCompensate("credit refused", at)
            .markCreditOutcomeUnknown("credit refused; refund outcome unknown: timeout", at)

        saga.state shouldBe SagaState.COMPENSATING
        saga.creditOutcomeUnknown shouldBe true
    }

    @Test
    fun `a refused credit is recorded as proof and survives into compensation`() {
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .markLedgerPosted(at)
            .markHoldCommitted(at)
            .markCreditRefused("account-service refused the request", at)

        saga.creditRefused shouldBe true
        saga.state shouldBe SagaState.LEDGER_POSTED
        // The permission to reverse has to outlive the transition, like holdCommitted does.
        saga.beginCompensate("credit refused", at).creditRefused shouldBe true
    }

    @Test
    fun `markCreditRefused rejects a saga whose hold is not committed`() {
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .markLedgerPosted(at)
        val ex = shouldThrow<DomainException> { saga.markCreditRefused("x", at) }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }

    @Test
    fun `a refund attempt can only be recorded while compensating a committed hold`() {
        val compensating = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .markLedgerPosted(at)
            .markHoldCommitted(at)
            .markCreditRefused("credit refused", at)
            .beginCompensate("credit refused", at)

        compensating.markRefundAttempted(at).refundAttempted shouldBe true

        // Before compensation begins there is nothing to refund, so the marker would be a lie.
        val tooEarly = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .markLedgerPosted(at)
            .markHoldCommitted(at)
        val ex = shouldThrow<DomainException> { tooEarly.markRefundAttempted(at) }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }

    @Test
    fun `a failed compensation stays COMPENSATING and keeps the reason`() {
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .beginCompensate("ledger down", at)
            .withCompensationFailure("ledger down; compensation failed: account-service unreachable", at)
        saga.state shouldBe SagaState.COMPENSATING
        saga.failureReason shouldBe "ledger down; compensation failed: account-service unreachable"
    }

    @Test
    fun `markCompensated rejects non-compensating state`() {
        val saga = TransferSaga.initiate(from, to, 10.lkr(), at)
        val ex = shouldThrow<DomainException> { saga.markCompensated(at) }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }

    @Test
    fun `step-up and block transitions from INITIATED`() {
        val awaiting = TransferSaga.initiate(from, to, 10.lkr(), at)
            .withRisk(55, "step_up", at)
            .markAwaitingStepUp(at)
        awaiting.state shouldBe SagaState.AWAITING_STEP_UP
        awaiting.riskScore shouldBe 55

        val blocked = TransferSaga.initiate(from, to, 10.lkr(), at)
            .markBlocked("velocity", at)
        blocked.state shouldBe SagaState.BLOCKED
        blocked.failureReason shouldBe "velocity"

        val blockedAfterStepUp = awaiting.markBlocked("otp failed", at)
        blockedAfterStepUp.state shouldBe SagaState.BLOCKED

        val reservedAfterStepUp = TransferSaga.initiate(from, to, 10.lkr(), at)
            .markAwaitingStepUp(at)
            .markReserved(at)
        reservedAfterStepUp.state shouldBe SagaState.FUNDS_RESERVED

        val ex = shouldThrow<DomainException> {
            TransferSaga.initiate(from, to, 10.lkr(), at).markReserved(at).markBlocked("late", at)
        }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }
}
