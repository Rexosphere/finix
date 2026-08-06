package org.finix.orchestrator.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.spyk
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletResponse
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.Money
import org.finix.kernel.domain.lkr
import org.finix.kernel.idempotency.IdempotencyFilter
import org.finix.kernel.idempotency.IdempotencyProperties
import org.finix.kernel.idempotency.InMemoryIdempotencyStore
import org.finix.kernel.messaging.EventEnvelope
import org.finix.kernel.web.GlobalExceptionHandler
import org.finix.orchestrator.application.port.AccountClient
import org.finix.orchestrator.application.port.LedgerClient
import org.finix.orchestrator.application.port.OutboxPort
import org.finix.orchestrator.application.port.RiskClient
import org.finix.orchestrator.application.port.SagaRepository
import org.finix.orchestrator.application.usecase.RunTransferSagaUseCase
import org.finix.orchestrator.domain.TransferSaga
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * B2: what the client is told when the recipient credit succeeded but the COMPLETED write did not.
 *
 * The status is not cosmetic here. [IdempotencyFilter] records any response below 500 and
 * *releases the key* at 500 and above, so answering this case with a 500 invites the client's
 * retry to run the whole transfer again against the same key — debiting the sender and crediting
 * the recipient a second time.
 */
class PostCreditFailureContractTest {

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

    private val accounts = object : AccountClient {
        override fun reserve(accountId: UUID, amount: Money, holdId: UUID) = Unit
        override fun commitHold(accountId: UUID, holdId: UUID) = Unit
        override fun releaseHold(accountId: UUID, holdId: UUID) = Unit
        override fun credit(accountId: UUID, amount: Money, reference: String) = Unit
    }

    private val ledger = object : LedgerClient {
        override fun postJournal(transactionId: UUID, lines: List<JournalLineCommand>) = Unit
    }

    private val risk = object : RiskClient {
        override fun scoreTransfer(
            transactionId: String,
            fromAccountId: String,
            toAccountId: String,
            amountMinor: Long,
            currency: String,
            velocity1h: Int,
            newDevice: Boolean,
            offlineVoucher: Boolean,
        ) = RiskAssessment(score = 5, decision = "allow")
    }

    @Test
    fun `a post-credit persistence failure is reported below 500 so the key is not released`() {
        val persistence = spyk(SagaPersistence(sagas, outbox))
        every { persistence.saveCompleted(any()) } throws IllegalStateException("saga store unavailable")
        val useCase = RunTransferSagaUseCase(persistence, sagas, accounts, ledger, risk, clock)

        val thrown = withClue("the failure must reach the client as a domain refusal, not a raw fault") {
            shouldThrow<DomainException> { useCase.execute(from, to, "100.00".lkr()) }
        }

        val problem = GlobalExceptionHandler().onDomainException(
            thrown,
            MockHttpServletRequest("POST", "/api/v1/transfers"),
        )

        withClue("IdempotencyFilter releases the key at >= 500, which lets the transfer run twice") {
            (problem.status < HttpStatus.INTERNAL_SERVER_ERROR.value()) shouldBe true
        }
        withClue("the client must not be told the transfer simply failed") {
            problem.detail!!.lowercase() shouldContain "reconcil"
        }
    }

    /**
     * Characterises the filter rule the fix depends on, so the link between "which status we
     * return" and "can the money move twice" is asserted rather than assumed.
     */
    @Test
    fun `the filter re-executes the body on 500 but replays it below 500`() {
        executionsForTwoAttemptsAt(HttpStatus.INTERNAL_SERVER_ERROR) shouldBe 2
        executionsForTwoAttemptsAt(HttpStatus.CONFLICT) shouldBe 1
    }

    private fun executionsForTwoAttemptsAt(status: HttpStatus): Int {
        val filter = IdempotencyFilter(
            InMemoryIdempotencyStore(),
            ObjectMapper().registerKotlinModule(),
            IdempotencyProperties(),
        )
        val executions = AtomicInteger()
        val chain = FilterChain { _: ServletRequest, response: ServletResponse ->
            executions.incrementAndGet()
            (response as HttpServletResponse).status = status.value()
        }
        repeat(2) { filter.doFilter(transferRequest(), MockHttpServletResponse(), chain) }
        return executions.get()
    }

    private fun transferRequest() =
        MockHttpServletRequest("POST", "/api/v1/transfers").apply {
            contentType = MediaType.APPLICATION_JSON_VALUE
            setContent("""{"fromAccountId":"$from","toAccountId":"$to","amount":"LKR 100.00"}""".toByteArray())
            addHeader(IdempotencyFilter.HEADER, "key-1")
        }
}
