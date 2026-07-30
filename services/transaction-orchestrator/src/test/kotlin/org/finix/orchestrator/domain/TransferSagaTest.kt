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
    fun `credit-applied can begin compensate`() {
        val saga = TransferSaga.initiate(from, to, 25.lkr(), at)
            .markReserved(at)
            .markLedgerPosted(at)
            .markCreditApplied(at)
            .beginCompensate("downstream timeout", at)
        saga.state shouldBe SagaState.COMPENSATING
        saga.ledgerPosted shouldBe true
    }

    @Test
    fun `markCompensated rejects non-compensating state`() {
        val saga = TransferSaga.initiate(from, to, 10.lkr(), at)
        val ex = shouldThrow<DomainException> { saga.markCompensated(at) }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }
}
