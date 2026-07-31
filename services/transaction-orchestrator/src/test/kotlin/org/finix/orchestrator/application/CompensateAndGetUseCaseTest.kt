package org.finix.orchestrator.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.lkr
import org.finix.kernel.messaging.EventEnvelope
import org.finix.orchestrator.application.port.AccountClient
import org.finix.orchestrator.application.port.LedgerClient
import org.finix.orchestrator.application.port.OutboxPort
import org.finix.orchestrator.application.port.SagaRepository
import org.finix.orchestrator.application.usecase.CompensateTransferSagaUseCase
import org.finix.orchestrator.application.usecase.GetTransferSagaUseCase
import org.finix.orchestrator.application.usecase.RunTransferSagaUseCase
import org.finix.orchestrator.domain.SagaState
import org.finix.orchestrator.domain.TransferSaga
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CompensateAndGetUseCaseTest {

    private val from = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val to = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-30T15:00:00Z"), ZoneOffset.UTC)
    private val store = ConcurrentHashMap<UUID, TransferSaga>()

    private val sagas = object : SagaRepository {
        override fun save(saga: TransferSaga): TransferSaga {
            store[saga.id] = saga
            return saga
        }

        override fun findById(id: UUID): TransferSaga? = store[id]
    }

    private val outbox = object : OutboxPort {
        override fun <T> append(topic: String, envelope: EventEnvelope<T>) = Unit
    }

    private val accounts = mockk<AccountClient>()
    private val ledger = mockk<LedgerClient>()
    private val risk = mockk<org.finix.orchestrator.application.port.RiskClient>()
    private val persistence = SagaPersistence(sagas, outbox)
    private val runTransfer = RunTransferSagaUseCase(persistence, sagas, accounts, ledger, risk, clock)
    private val getTransfer = GetTransferSagaUseCase(sagas)
    private val compensate = CompensateTransferSagaUseCase(sagas, runTransfer)

    @Test
    fun `get returns persisted saga`() {
        val saga = TransferSaga.initiate(from, to, 10.lkr(), Instant.now(clock))
        sagas.save(saga)
        getTransfer.execute(saga.id).id shouldBe saga.id
    }

    @Test
    fun `get missing saga is NotFound`() {
        val ex = shouldThrow<DomainException> { getTransfer.execute(UUID.randomUUID()) }
        ex.error.shouldBeInstanceOf<DomainError.NotFound>()
    }

    @Test
    fun `admin compensate releases reserved funds`() {
        val saga = TransferSaga.initiate(from, to, 40.lkr(), Instant.now(clock))
            .markReserved(Instant.now(clock))
        sagas.save(saga)
        every { accounts.releaseHold(any(), any()) } just runs

        val result = compensate.execute(saga.id)
        result.state shouldBe SagaState.COMPENSATED
        verify(exactly = 1) { accounts.releaseHold(from, saga.holdId) }
    }

    @Test
    fun `admin compensate on completed is Conflict`() {
        val saga = TransferSaga.initiate(from, to, 40.lkr(), Instant.now(clock))
            .markReserved(Instant.now(clock))
            .markLedgerPosted(Instant.now(clock))
            .markCreditApplied(Instant.now(clock))
            .markCompleted(Instant.now(clock))
        sagas.save(saga)

        val ex = shouldThrow<DomainException> { compensate.execute(saga.id) }
        ex.error.shouldBeInstanceOf<DomainError.Conflict>()
    }

    @Test
    fun `admin compensate missing is NotFound`() {
        val ex = shouldThrow<DomainException> { compensate.execute(UUID.randomUUID()) }
        ex.error.shouldBeInstanceOf<DomainError.NotFound>()
    }

    @Test
    fun `execute rejects same-account transfer`() {
        val ex = shouldThrow<DomainException> {
            runTransfer.execute(from, from, 10.lkr())
        }
        ex.error.shouldBeInstanceOf<DomainError.Invalid>()
    }

    @Test
    fun `execute rejects non-positive amount`() {
        val ex = shouldThrow<DomainException> {
            runTransfer.execute(from, to, 0.lkr())
        }
        ex.error.shouldBeInstanceOf<DomainError.Invalid>()
    }
}
