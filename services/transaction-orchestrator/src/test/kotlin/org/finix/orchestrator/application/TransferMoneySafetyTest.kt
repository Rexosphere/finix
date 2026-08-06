package org.finix.orchestrator.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.spyk
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.kernel.domain.Money
import org.finix.kernel.domain.lkr
import org.finix.kernel.messaging.EventEnvelope
import org.finix.kernel.messaging.Topics
import org.finix.orchestrator.application.port.AccountClient
import org.finix.orchestrator.application.port.LedgerClient
import org.finix.orchestrator.application.port.OutboxPort
import org.finix.orchestrator.application.port.RiskClient
import org.finix.orchestrator.application.port.SagaRepository
import org.finix.orchestrator.application.usecase.CompensateTransferSagaUseCase
import org.finix.orchestrator.application.usecase.RunTransferSagaUseCase
import org.finix.orchestrator.domain.SagaState
import org.finix.orchestrator.domain.TransferSaga
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Money-safety of the transfer saga under partial failure.
 *
 * Unlike the mock-based tests, these run against fakes that reproduce the *real* downstream
 * money model — in particular the two rules that decide whether a compensation is valid at all:
 * a COMMITTED hold cannot be released (`Account.releaseHold`), and a credit is not idempotent
 * (`CreditAccountUseCase` ignores its reference). Assertions are therefore about balances and
 * the ledger, not about which methods were called.
 *
 * Invariants asserted at every terminal outcome:
 *  1. conservation — the sum of all balances equals the opening sum (no money created or lost);
 *  2. no stranded holds — nothing is left sitting in `held` with no way to resolve it;
 *  3. account/ledger agreement — each account's net journal movement equals its balance change.
 */
class TransferMoneySafetyTest {

    private val from = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val to = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-30T15:00:00Z"), ZoneOffset.UTC)

    private val opening = mapOf(from to "1000.00".lkr(), to to "500.00".lkr())
    private val openingTotal = opening.values.reduce(Money::plus)
    private val amount = "100.00".lkr()
    private val zero = Money.zero(Money.LKR)

    private val accounts = AccountsFake(opening)
    private val ledger = LedgerFake()

    private val store = ConcurrentHashMap<UUID, TransferSaga>()

    /**
     * Enforces the same monotonic merge the real UPSERT performs (D-3), so that *every* test in
     * this class doubles as a check that no code path ever writes a stale safety fact back to
     * FALSE. A downgrade is a defect, not something the fake should silently absorb.
     */
    private val sagas = object : SagaRepository {
        override fun save(saga: TransferSaga): TransferSaga {
            store.compute(saga.id) { _, previous ->
                previous?.let { requireMonotonicSafetyFacts(it, saga) }
                saga
            }
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

    private val persistence = SagaPersistence(sagas, outbox)
    private val useCase = RunTransferSagaUseCase(persistence, sagas, accounts, ledger, risk, clock)
    private val adminCompensate = CompensateTransferSagaUseCase(sagas, useCase)

    // ---------------------------------------------------------------- controls (must keep passing)

    @Test
    fun `a completed transfer conserves money and agrees with the ledger`() {
        val result = useCase.execute(from, to, amount)

        result.state shouldBe SagaState.COMPLETED
        assertConserved()
        accounts.available(from) shouldBe opening.getValue(from) - amount
        accounts.available(to) shouldBe opening.getValue(to) + amount
        assertLedgerAgreesWithBalances()
    }

    @Test
    fun `case A - a failed reserve leaves every balance untouched`() {
        accounts.reserveFaults += Fault.REFUSE

        useCase.execute(from, to, amount).state shouldBe SagaState.FAILED

        assertConserved()
        accounts.available(from) shouldBe opening.getValue(from)
        assertLedgerAgreesWithBalances()
    }

    @Test
    fun `case B - a failed ledger post releases the hold and restores the sender`() {
        ledger.postFaults += Fault.UNAVAILABLE

        useCase.execute(from, to, amount).state shouldBe SagaState.COMPENSATED

        assertConserved()
        accounts.available(from) shouldBe opening.getValue(from)
        accounts.held(from) shouldBe zero
        assertLedgerAgreesWithBalances()
    }

    // ---------------------------------------------------------------- proven unsafe windows

    /** Case D: the hold is committed, then the recipient credit is definitively refused. */
    @Test
    fun `case D - a refused credit after the hold is committed must not lose the sender's money`() {
        accounts.creditFaults += Fault.REFUSE

        val result = useCase.execute(from, to, amount)

        withClue("money must not disappear when compensation runs after a committed hold") {
            assertConserved()
        }
        accounts.available(from) shouldBe opening.getValue(from)
        accounts.held(from) shouldBe zero
        accounts.available(to) shouldBe opening.getValue(to)
        result.state shouldBe SagaState.COMPENSATED
        assertLedgerAgreesWithBalances()
    }

    /**
     * B1: account-service answers the release with a 409 because its optimistic lock lost a race.
     * The hold is still OPEN and nothing was applied, so inferring "the commit landed" from that
     * Conflict and paying the sender would create money out of a retryable transient.
     */
    @Test
    fun `case B1 - a concurrent release failure must not refund a still-open hold`() {
        ledger.postFaults += Fault.UNAVAILABLE
        accounts.releaseFaults += Fault.CONCURRENT_MODIFICATION

        val result = useCase.execute(from, to, amount)

        withClue("refunding a hold that is still OPEN creates money") {
            assertConserved()
        }
        accounts.available(from) shouldBe opening.getValue(from) - amount
        accounts.held(from) shouldBe amount
        result.state shouldBe SagaState.COMPENSATING

        // The saga must stay recoverable once the race clears.
        val retried = adminCompensate.execute(result.id)

        retried.state shouldBe SagaState.COMPENSATED
        assertConserved()
        accounts.available(from) shouldBe opening.getValue(from)
        accounts.held(from) shouldBe zero
    }

    /**
     * Case C': the commit landed but the reply was lost, so `holdCommitted` was never persisted.
     * The orchestrator cannot tell this apart from a commit that never happened, so it must not
     * guess — an unresolved transfer is preferable to an invented refund.
     */
    @Test
    fun `case C prime - an unconfirmed commit is left unresolved rather than guessed`() {
        accounts.commitFaults += Fault.LOST_RESPONSE

        val result = useCase.execute(from, to, amount)

        withClue("the commit was never confirmed to us, so the sender must not be paid back") {
            accounts.available(from) shouldBe opening.getValue(from) - amount
        }
        accounts.available(to) shouldBe opening.getValue(to)
        withClue("the transfer must remain reconcilable rather than terminal") {
            result.state shouldBe SagaState.COMPENSATING
        }
    }

    /**
     * B3: the recipient was credited but the reply was lost. Neither the saga nor an operator may
     * reverse the sender's debit until the recipient's side has been reconciled.
     */
    @Test
    fun `case B3 - an unknown credit outcome is never reversed, by the saga or by an operator`() {
        accounts.creditFaults += Fault.LOST_RESPONSE

        val result = useCase.execute(from, to, amount)

        withClue("the recipient already has the money; refunding the sender would create money") {
            assertConserved()
        }
        accounts.available(to) shouldBe opening.getValue(to) + amount
        withClue("no terminal failure event may be published for a transfer that moved money") {
            outboxTopics shouldBe listOf(Topics.TRANSACTION_INITIATED)
        }

        withClue("admin compensation must refuse a transfer whose credit outcome is unknown") {
            shouldThrow<DomainException> { adminCompensate.execute(result.id) }
        }
        assertConserved()
        accounts.available(from) shouldBe opening.getValue(from) - amount
    }

    /** Case E: the credit succeeded but the COMPLETED write did not. */
    @Test
    fun `case E - a transfer whose credit succeeded is never reversible afterwards`() {
        val failing = spyk(persistence)
        every { failing.saveCompleted(any()) } throws IllegalStateException("saga store unavailable")
        val brittle = RunTransferSagaUseCase(failing, sagas, accounts, ledger, risk, clock)

        runCatching { brittle.execute(from, to, amount) }

        withClue("the money moved exactly once") {
            assertConserved()
            accounts.available(from) shouldBe opening.getValue(from) - amount
            accounts.available(to) shouldBe opening.getValue(to) + amount
        }

        val sagaId = store.keys.single()
        withClue("compensating now would refund the sender while the recipient keeps the credit") {
            shouldThrow<DomainException> { adminCompensate.execute(sagaId) }
        }
        assertConserved()
    }

    /** Case H: compensation itself fails; the hold must not be stranded beyond recovery. */
    @Test
    fun `case H - a failed compensation stays retryable instead of stranding the hold`() {
        ledger.postFaults += Fault.UNAVAILABLE
        accounts.releaseFaults += Fault.UNAVAILABLE

        val result = useCase.execute(from, to, amount)

        withClue("the release failed, so the funds are still held at this point") {
            accounts.held(from) shouldBe amount
        }

        val retried = adminCompensate.execute(result.id)

        retried.state shouldBe SagaState.COMPENSATED
        assertConserved()
        accounts.available(from) shouldBe opening.getValue(from)
        accounts.held(from) shouldBe zero
    }

    /**
     * D1: the recipient credit is definitively refused, so the committed debit is correctly
     * refunded — but the refund's own reply is lost. `credit` is not idempotent downstream, so
     * re-issuing it on an operator replay would pay the sender a second time.
     */
    @Test
    fun `case D1 - a refund whose response was lost is never issued twice`() {
        accounts.creditFaults += Fault.REFUSE // recipient credit definitively refused
        accounts.creditFaults += Fault.LOST_RESPONSE // refund lands, reply never arrives

        val result = useCase.execute(from, to, amount)

        withClue("the refund did reach the sender exactly once") {
            accounts.appliedCreditCount(from) shouldBe 1
            accounts.available(from) shouldBe opening.getValue(from)
        }
        accounts.available(to) shouldBe opening.getValue(to)
        assertConserved()

        val replay = runCatching { adminCompensate.execute(result.id) }

        withClue("a replayed compensation must not credit the sender a second time") {
            accounts.appliedCreditCount(from) shouldBe 1
            accounts.available(from) shouldBe opening.getValue(from)
            assertConserved()
        }
        withClue("the replay must be refused while the refund outcome is unknown") {
            replay.isFailure shouldBe true
        }
    }

    /**
     * A refund that was definitively refused genuinely changed nothing — but nothing *durable*
     * distinguishes that from a refusal raised after the credit was applied, so the saga is
     * reconciled by hand rather than replayed. This is the availability cost of D-2's guarantee,
     * accepted deliberately: the alternative is a replay that can pay the sender twice.
     */
    @Test
    fun `case D1 - a definitively refused refund is reconciled rather than replayed`() {
        accounts.creditFaults += Fault.REFUSE // recipient credit definitively refused
        accounts.creditFaults += Fault.REFUSE // refund refused outright — nothing applied

        val result = useCase.execute(from, to, amount)

        result.state shouldBe SagaState.COMPENSATING
        result.refundAttempted shouldBe true
        accounts.appliedCreditCount(from) shouldBe 0
        accounts.available(from) shouldBe opening.getValue(from) - amount

        // Even with the fault queue empty, the replay must not re-enter a non-idempotent credit.
        shouldThrow<DomainException> { adminCompensate.execute(result.id) }

        withClue("the sender is still owed the amount, but nothing was paid out twice") {
            accounts.appliedCreditCount(from) shouldBe 0
            accounts.available(from) shouldBe opening.getValue(from) - amount
            accounts.available(to) shouldBe opening.getValue(to)
        }
    }

    /** The refund credit is not idempotent downstream, so a replay must never reach it twice. */
    @Test
    fun `replaying compensation after a refund cannot pay the sender twice`() {
        accounts.creditFaults += Fault.REFUSE
        val result = useCase.execute(from, to, amount)
        result.state shouldBe SagaState.COMPENSATED
        assertConserved()

        shouldThrow<DomainException> { adminCompensate.execute(result.id) }

        assertConserved()
        accounts.available(from) shouldBe opening.getValue(from)
    }

    // ---------------------------------------------------------------- D-1: unrecorded credit outcome

    /**
     * D-1 A: the recipient credit succeeded and the *first* post-credit write failed, so the only
     * durable trace is `LEDGER_POSTED + holdCommitted`. That row is indistinguishable from a
     * credit that never happened, so compensating it would refund a sender whose recipient has
     * already been paid.
     */
    @Test
    fun `D1 A - a failed post-credit write leaves a saga that must not be refunded`() {
        val failing = spyk(persistence)
        every { failing.save(match { it.state == SagaState.CREDIT_APPLIED }) } throws
            IllegalStateException("saga store unavailable")
        val brittle = RunTransferSagaUseCase(failing, sagas, accounts, ledger, risk, clock)
        val admin = CompensateTransferSagaUseCase(sagas, brittle)

        runCatching { brittle.execute(from, to, amount) }

        val durable = sagas.findById(store.keys.single())!!
        withClue("the durable row is the ambiguous one this case is about") {
            durable.state shouldBe SagaState.LEDGER_POSTED
            durable.holdCommitted shouldBe true
        }
        withClue("the recipient really was credited") {
            accounts.available(to) shouldBe opening.getValue(to) + amount
        }

        shouldThrow<DomainException> { admin.execute(durable.id) }

        withClue("refunding here would pay the amount out twice") {
            accounts.appliedCreditCount(from) shouldBe 0
            accounts.available(from) shouldBe opening.getValue(from) - amount
            assertConserved()
        }
    }

    /**
     * D-1 B: the process stopped before any credit outcome was recorded. The saga must not guess:
     * a financial reversal is permitted only with persistent proof that the credit definitively
     * failed, and no such proof exists here.
     */
    @Test
    fun `D1 B - an unrecorded credit outcome after a committed hold cannot be guessed`() {
        val crashed = TransferSaga.initiate(from, to, amount, Instant.now(clock))
            .markReserved(Instant.now(clock))
            .markLedgerPosted(Instant.now(clock))
            .markHoldCommitted(Instant.now(clock))
        sagas.save(crashed)
        // What account-service actually did before the orchestrator died: hold committed, and the
        // recipient credit landed — the outcome the lost process never got to record.
        accounts.reserve(from, amount, crashed.holdId)
        accounts.commitHold(from, crashed.holdId)
        accounts.credit(to, amount, reference = crashed.id.toString())

        shouldThrow<DomainException> { adminCompensate.execute(crashed.id) }

        withClue("no reversal may happen without proof the credit failed") {
            accounts.appliedCreditCount(from) shouldBe 0
            accounts.available(from) shouldBe opening.getValue(from) - amount
            accounts.available(to) shouldBe opening.getValue(to) + amount
            assertConserved()
        }
    }

    // ---------------------------------------------------------------- D-2: refund replay

    /**
     * D-2: the recipient credit was definitively refused, the compensating refund reached the
     * sender, and only the terminal COMPENSATED write failed. The saga is left retryable, so an
     * operator replay would re-issue a credit that account-service does not deduplicate.
     */
    @Test
    fun `D2 - a refund whose terminal write failed is never issued a second time`() {
        accounts.creditFaults += Fault.REFUSE // recipient credit definitively refused
        val failing = spyk(persistence)
        every { failing.saveTerminalFailure(match { it.state == SagaState.COMPENSATED }) } throws
            IllegalStateException("saga store unavailable")
        val brittle = RunTransferSagaUseCase(failing, sagas, accounts, ledger, risk, clock)
        val admin = CompensateTransferSagaUseCase(sagas, brittle)

        runCatching { brittle.execute(from, to, amount) }

        val durable = sagas.findById(store.keys.single())!!
        durable.state shouldBe SagaState.COMPENSATING
        withClue("the refund reached the sender exactly once") {
            accounts.appliedCreditCount(from) shouldBe 1
            accounts.available(from) shouldBe opening.getValue(from)
            assertConserved()
        }

        shouldThrow<DomainException> { admin.execute(durable.id) }

        withClue("a replayed compensation must not pay the sender a second time") {
            accounts.appliedCreditCount(from) shouldBe 1
            accounts.available(from) shouldBe opening.getValue(from)
            assertConserved()
        }
    }

    // ---------------------------------------------------------------- D-3: monotonic safety facts

    @Test
    fun `D3 - a stale save cannot clear creditOutcomeUnknown`() {
        val frozen = TransferSaga.initiate(from, to, amount, Instant.now(clock))
            .markReserved(Instant.now(clock))
            .markLedgerPosted(Instant.now(clock))
            .markHoldCommitted(Instant.now(clock))
            .markCreditOutcomeUnknown("credit outcome unknown", Instant.now(clock))
        sagas.save(frozen)

        shouldThrow<IllegalStateException> {
            sagas.save(frozen.copy(creditOutcomeUnknown = false))
        }
    }

    @Test
    fun `D3 - a stale save cannot clear refundAttempted or holdCommitted`() {
        val refunding = TransferSaga.initiate(from, to, amount, Instant.now(clock))
            .markReserved(Instant.now(clock))
            .markLedgerPosted(Instant.now(clock))
            .markHoldCommitted(Instant.now(clock))
            .markCreditRefused("credit refused", Instant.now(clock))
            .beginCompensate("credit refused", Instant.now(clock))
            .markRefundAttempted(Instant.now(clock))
        sagas.save(refunding)

        shouldThrow<IllegalStateException> {
            sagas.save(refunding.copy(refundAttempted = false))
        }
        shouldThrow<IllegalStateException> {
            sagas.save(refunding.copy(holdCommitted = false))
        }
        shouldThrow<IllegalStateException> {
            sagas.save(refunding.copy(creditRefused = false))
        }
    }

    // ---------------------------------------------------------------- invariants

    /**
     * A safety fact whose TRUE value means "do not financially reverse this transfer" must never
     * be downgraded — see `JdbcSagaRepository.UPSERT`, which merges these with OR rather than
     * taking the incoming value.
     */
    private fun requireMonotonicSafetyFacts(previous: TransferSaga, next: TransferSaga) {
        listOf(
            Triple("ledgerPosted", previous.ledgerPosted, next.ledgerPosted),
            Triple("holdCommitted", previous.holdCommitted, next.holdCommitted),
            Triple("creditOutcomeUnknown", previous.creditOutcomeUnknown, next.creditOutcomeUnknown),
            Triple("creditRefused", previous.creditRefused, next.creditRefused),
            Triple("refundAttempted", previous.refundAttempted, next.refundAttempted),
        ).forEach { (fact, wasSet, stillSet) ->
            check(wasSet <= stillSet) { "safety fact $fact was cleared TRUE → FALSE by a stale save" }
        }
    }

    private fun assertConserved() {
        withClue("total money across all accounts") {
            accounts.total shouldBe openingTotal
        }
    }

    private fun assertLedgerAgreesWithBalances() {
        listOf(from, to).forEach { id ->
            val movement = accounts.available(id) + accounts.held(id) - opening.getValue(id)
            withClue("ledger movement must equal the balance change for $id") {
                ledger.netMovement(id) shouldBe movement
            }
        }
    }
}

/**
 * How a downstream call fails.
 *
 * [CONCURRENT_MODIFICATION] is the case the earlier version of this fake could not express:
 * account-service raises `DomainError.ConcurrentModification` when its optimistic-lock
 * `UPDATE … WHERE version = :version` loses a race, that maps to HTTP 409, and `callDownstream`
 * flattens every 4xx into `DomainError.Conflict` — the *same* error a COMMITTED hold produces,
 * even though nothing was applied and the operation is retryable.
 */
private enum class Fault { REFUSE, UNAVAILABLE, LOST_RESPONSE, CONCURRENT_MODIFICATION }

private const val ACCOUNT_DEPENDENCY = "account-service"

private fun Fault.raise(dependency: String): Nothing = when (this) {
    Fault.REFUSE -> DomainError.Conflict("$dependency refused the request").raise()
    // What the orchestrator actually sees for a 409, after callDownstream has flattened it.
    Fault.CONCURRENT_MODIFICATION ->
        DomainError.Conflict("$dependency refused the request with HTTP 409").raise()
    else -> DomainError.Unavailable(dependency, "$dependency is unreachable").raise()
}

/** Runs [effect] unless the next scripted fault says otherwise; LOST_RESPONSE applies it first. */
private fun ArrayDeque<Fault>.gate(dependency: String, effect: () -> Unit) {
    when (val fault = removeFirstOrNull()) {
        null -> effect()
        Fault.LOST_RESPONSE -> {
            effect()
            fault.raise(dependency)
        }
        else -> fault.raise(dependency)
    }
}

/**
 * Faithful stand-in for account-service: the same hold lifecycle, the same refusals, and the
 * same non-idempotent credit. Anything this fake permits, the real service permits.
 */
private class AccountsFake(opening: Map<UUID, Money>) : AccountClient {

    private enum class HoldStatus { OPEN, COMMITTED, RELEASED }

    private data class HoldRecord(val accountId: UUID, val amount: Money, val status: HoldStatus)

    private val availableBalances = opening.toMutableMap()
    private val heldBalances = opening.mapValues { Money.zero(it.value.currency) }.toMutableMap()
    private val holds = mutableMapOf<UUID, HoldRecord>()
    private val appliedCredits = mutableListOf<UUID>()

    val reserveFaults = ArrayDeque<Fault>()
    val commitFaults = ArrayDeque<Fault>()
    val releaseFaults = ArrayDeque<Fault>()
    val creditFaults = ArrayDeque<Fault>()

    fun available(accountId: UUID): Money = availableBalances.getValue(accountId)

    fun held(accountId: UUID): Money = heldBalances.getValue(accountId)

    val total: Money
        get() = (availableBalances.values + heldBalances.values).reduce(Money::plus)

    override fun reserve(accountId: UUID, amount: Money, holdId: UUID) =
        reserveFaults.gate(ACCOUNT_DEPENDENCY) {
            val existing = holds[holdId]
            if (existing != null) return@gate // idempotent on holdId
            if (available(accountId) < amount) {
                DomainError.InsufficientFunds(accountId.toString(), amount, available(accountId)).raise()
            }
            availableBalances[accountId] = available(accountId) - amount
            heldBalances[accountId] = held(accountId) + amount
            holds[holdId] = HoldRecord(accountId, amount, HoldStatus.OPEN)
        }

    override fun commitHold(accountId: UUID, holdId: UUID) =
        commitFaults.gate(ACCOUNT_DEPENDENCY) {
            val hold = requireHold(holdId)
            if (hold.status == HoldStatus.COMMITTED) return@gate // idempotent
            requireOpen(hold, "committed")
            heldBalances[accountId] = held(accountId) - hold.amount
            holds[holdId] = hold.copy(status = HoldStatus.COMMITTED)
        }

    override fun releaseHold(accountId: UUID, holdId: UUID) =
        releaseFaults.gate(ACCOUNT_DEPENDENCY) {
            val hold = requireHold(holdId)
            if (hold.status == HoldStatus.RELEASED) return@gate // idempotent
            requireOpen(hold, "released")
            heldBalances[accountId] = held(accountId) - hold.amount
            availableBalances[accountId] = available(accountId) + hold.amount
            holds[holdId] = hold.copy(status = HoldStatus.RELEASED)
        }

    /** Deliberately not idempotent — CreditAccountUseCase ignores the reference. */
    override fun credit(accountId: UUID, amount: Money, reference: String) =
        creditFaults.gate(ACCOUNT_DEPENDENCY) {
            appliedCredits += accountId
            availableBalances[accountId] = available(accountId) + amount
        }

    /** How many credits actually took effect on [accountId] — a refund must appear exactly once. */
    fun appliedCreditCount(accountId: UUID): Int = appliedCredits.count { it == accountId }

    private fun requireHold(holdId: UUID): HoldRecord =
        holds[holdId] ?: DomainError.NotFound("Hold", holdId.toString()).raise()

    private fun requireOpen(hold: HoldRecord, verb: String) {
        if (hold.status != HoldStatus.OPEN) {
            DomainError.Conflict("Hold cannot be $verb from status ${hold.status}").raise()
        }
    }
}

/** Stand-in for ledger-service: append-only, and a duplicate transactionId is a Conflict. */
private class LedgerFake : LedgerClient {

    private val entries = linkedMapOf<UUID, List<JournalLineCommand>>()

    val postFaults = ArrayDeque<Fault>()

    override fun postJournal(transactionId: UUID, lines: List<JournalLineCommand>) =
        postFaults.gate("ledger-service") {
            if (entries.containsKey(transactionId)) {
                DomainError.Conflict("Journal for transaction $transactionId already exists").raise()
            }
            entries[transactionId] = lines
        }

    /** Net effect the journals claim for [accountId]: credits minus debits. */
    fun netMovement(accountId: UUID): Money =
        entries.values.flatten()
            .filter { it.accountId == accountId }
            .fold(Money.zero(Money.LKR)) { acc, line ->
                if (line.side == JournalSide.CREDIT) acc + line.amount else acc - line.amount
            }
}
