package org.finix.orchestrator.application

import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import io.mockk.Called
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.lkr
import org.finix.kernel.messaging.EventEnvelope
import org.finix.kernel.messaging.Topics
import org.finix.orchestrator.application.RiskAssessment
import org.finix.orchestrator.application.port.AccountClient
import org.finix.orchestrator.application.port.LedgerClient
import org.finix.orchestrator.application.port.OutboxPort
import org.finix.orchestrator.application.port.RiskClient
import org.finix.orchestrator.application.port.SagaRepository
import org.finix.orchestrator.application.usecase.RunTransferSagaUseCase
import org.finix.orchestrator.domain.SagaState
import org.finix.orchestrator.domain.TransferSaga
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RunTransferSagaUseCaseTest {

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

    private val outboxTopics = mutableListOf<String>()
    private val outbox = object : OutboxPort {
        override fun <T> append(topic: String, envelope: EventEnvelope<T>) {
            outboxTopics += topic
        }
    }

    private val accounts = mockk<AccountClient>()
    private val ledger = mockk<LedgerClient>()
    private val risk = mockk<RiskClient>()
    private lateinit var persistence: SagaPersistence
    private lateinit var useCase: RunTransferSagaUseCase

    @BeforeEach
    fun setUp() {
        store.clear()
        outboxTopics.clear()
        clearMocks(accounts, ledger, risk)
        every {
            risk.scoreTransfer(any(), any(), any(), any(), any(), any(), any(), any())
        } returns RiskAssessment(score = 10, decision = "allow", reasons = emptyList())
        persistence = SagaPersistence(sagas, outbox)
        useCase = RunTransferSagaUseCase(persistence, sagas, accounts, ledger, risk, clock)
    }

    @Test
    fun `happy path reserves posts commits and credits`() {
        every { accounts.reserve(any(), any(), any()) } just runs
        every { ledger.postJournal(any(), any()) } just runs
        every { accounts.commitHold(any(), any()) } just runs
        every { accounts.credit(any(), any(), any()) } just runs

        val result = useCase.execute(from, to, "100.00".lkr())

        result.state shouldBe SagaState.COMPLETED
        outboxTopics shouldBe listOf(Topics.TRANSACTION_INITIATED, Topics.TRANSACTION_COMMITTED)
        verifyOrder {
            accounts.reserve(from, "100.00".lkr(), result.holdId)
            ledger.postJournal(result.id, any())
            accounts.commitHold(from, result.holdId)
            accounts.credit(to, "100.00".lkr(), result.id.toString())
        }
    }

    @Test
    fun `ledger failure releases hold and compensates without reversal`() {
        every { accounts.reserve(any(), any(), any()) } just runs
        every { ledger.postJournal(any(), any()) } throws
            DomainException(DomainError.Unavailable("ledger-service", "connection refused"))
        every { accounts.releaseHold(any(), any()) } just runs

        val result = useCase.execute(from, to, 50.lkr())

        result.state shouldBe SagaState.COMPENSATED
        outboxTopics shouldBe listOf(Topics.TRANSACTION_INITIATED, Topics.TRANSACTION_FAILED)
        verify(exactly = 1) { accounts.releaseHold(from, result.holdId) }
        // Only the failing forward journal was attempted — no reversal call.
        verify(exactly = 1) { ledger.postJournal(any(), any()) }
        verify(exactly = 0) { accounts.commitHold(any(), any()) }
    }

    @Test
    fun `failure after ledger posts reversing journal then releases`() {
        every { accounts.reserve(any(), any(), any()) } just runs
        every { ledger.postJournal(any(), any()) } just runs
        every { accounts.commitHold(any(), any()) } throws
            DomainException(DomainError.Unavailable("account-service", "commit failed"))
        every { accounts.releaseHold(any(), any()) } just runs

        val result = useCase.execute(from, to, 75.lkr())

        result.state shouldBe SagaState.COMPENSATED
        verify(exactly = 2) { ledger.postJournal(any(), any()) }
        verify(exactly = 1) {
            ledger.postJournal(RunTransferSagaUseCase.reversalTransactionId(result.id), any())
        }
        verify { accounts.releaseHold(from, result.holdId) }
        outboxTopics.last() shouldBe Topics.TRANSACTION_FAILED
    }

    @Test
    fun `reserve failure marks FAILED without compensation calls`() {
        every { accounts.reserve(any(), any(), any()) } throws
            DomainException(DomainError.InsufficientFunds(from.toString(), 10.lkr(), 0.lkr()))

        val result = useCase.execute(from, to, 10.lkr())

        result.state shouldBe SagaState.FAILED
        verify { ledger wasNot Called }
        verify(exactly = 0) { accounts.releaseHold(any(), any()) }
        outboxTopics shouldBe listOf(Topics.TRANSACTION_INITIATED, Topics.TRANSACTION_FAILED)
    }

    @Test
    fun `step_up decision suspends before reserve`() {
        every {
            risk.scoreTransfer(any(), any(), any(), any(), any(), any(), any(), any())
        } returns RiskAssessment(score = 55, decision = "step_up", reasons = listOf("new_device"))

        val result = useCase.execute(from, to, "100.00".lkr(), newDevice = true)

        result.state shouldBe SagaState.AWAITING_STEP_UP
        result.riskScore shouldBe 55
        verify { accounts wasNot Called }
        verify { ledger wasNot Called }

        every { accounts.reserve(any(), any(), any()) } just runs
        every { ledger.postJournal(any(), any()) } just runs
        every { accounts.commitHold(any(), any()) } just runs
        every { accounts.credit(any(), any(), any()) } just runs

        val resumed = useCase.completeStepUp(result.id, "123456")
        resumed.state shouldBe SagaState.COMPLETED
    }

    @Test
    fun `block decision is terminal without touching accounts`() {
        every {
            risk.scoreTransfer(any(), any(), any(), any(), any(), any(), any(), any())
        } returns RiskAssessment(score = 88, decision = "block", reasons = listOf("amount"), caseId = "case-1")

        val result = useCase.execute(from, to, "600000.00".lkr())

        result.state shouldBe SagaState.BLOCKED
        verify { accounts wasNot Called }
        outboxTopics shouldBe listOf(Topics.TRANSACTION_INITIATED, Topics.TRANSACTION_FAILED)
    }

    @Test
    fun `non-domain reserve failure uses exception message in failure reason`() {
        every { accounts.reserve(any(), any(), any()) } throws IllegalStateException("boom")

        val result = useCase.execute(from, to, 10.lkr())

        result.state shouldBe SagaState.FAILED
        result.failureReason shouldBe "boom"
    }
}
