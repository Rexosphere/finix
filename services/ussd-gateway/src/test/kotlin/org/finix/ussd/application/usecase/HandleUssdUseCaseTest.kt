package org.finix.ussd.application.usecase

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.finix.kernel.domain.lkr
import org.finix.ussd.application.AccountBalanceView
import org.finix.ussd.application.TransferResult
import org.finix.ussd.adapter.out.copy.TrilingualCopyCatalog
import org.finix.ussd.application.port.AccountClient
import org.finix.ussd.application.port.TransferClient
import org.finix.ussd.application.port.UssdSessionStore
import org.finix.ussd.domain.UssdDirectory
import org.finix.ussd.domain.UssdSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class HandleUssdUseCaseTest : StringSpec({

    fun memoryStore(): UssdSessionStore = object : UssdSessionStore {
        private val map = ConcurrentHashMap<String, UssdSession>()
        override fun load(sessionId: String) = map[sessionId]
        override fun save(session: UssdSession) {
            map[session.sessionId] = session
        }
        override fun clear(sessionId: String) {
            map.remove(sessionId)
        }
    }

    "welcome menu on empty text" {
        val accounts = mockk<AccountClient>()
        val transfers = mockk<TransferClient>()
        val useCase = HandleUssdUseCase(memoryStore(), accounts, transfers, TrilingualCopyCatalog())
        val reply = useCase.execute("s1", UssdDirectory.FARMER.phone, "")
        reply shouldStartWith "CON "
        reply.contains("1 Balance") shouldBe true
    }

    "balance ends the session" {
        val accounts = mockk<AccountClient>()
        every { accounts.getBalance(UssdDirectory.FARMER.accountId) } returns AccountBalanceView(
            accountId = UssdDirectory.FARMER.accountId,
            accountNumber = UssdDirectory.FARMER.accountNumber,
            available = "25000.00".lkr(),
            held = "0.00".lkr(),
        )
        val useCase = HandleUssdUseCase(memoryStore(), accounts, mockk(), TrilingualCopyCatalog())
        val reply = useCase.execute("s2", UssdDirectory.FARMER.phone, "1")
        reply shouldStartWith "END "
        reply.contains("25000") shouldBe true
    }

    "send money happy path" {
        val accounts = mockk<AccountClient>()
        val transfers = mockk<TransferClient>()
        every {
            transfers.transfer(UssdDirectory.FARMER.accountId, UssdDirectory.SME.accountId, any())
        } returns TransferResult(UUID.randomUUID(), "COMPLETED")
        val store = memoryStore()
        val useCase = HandleUssdUseCase(store, accounts, transfers, TrilingualCopyCatalog())

        useCase.execute("s3", UssdDirectory.FARMER.phone, "") shouldStartWith "CON "
        useCase.execute("s3", UssdDirectory.FARMER.phone, "2") shouldStartWith "CON "
        useCase.execute("s3", UssdDirectory.FARMER.phone, "2*${UssdDirectory.SME.accountNumber}") shouldStartWith "CON "
        useCase.execute(
            "s3",
            UssdDirectory.FARMER.phone,
            "2*${UssdDirectory.SME.accountNumber}*100.00",
        ) shouldStartWith "CON "
        val done = useCase.execute(
            "s3",
            UssdDirectory.FARMER.phone,
            "2*${UssdDirectory.SME.accountNumber}*100.00*1",
        )
        done shouldStartWith "END "
        done.contains("Sent") shouldBe true
        verify(exactly = 1) {
            transfers.transfer(UssdDirectory.FARMER.accountId, UssdDirectory.SME.accountId, "100.00".lkr())
        }
    }

    "unknown phone is rejected" {
        val useCase = HandleUssdUseCase(memoryStore(), mockk(), mockk(), TrilingualCopyCatalog())
        val reply = useCase.execute("s4", "+94770000000", "")
        reply shouldStartWith "END "
        reply.contains("not registered") shouldBe true
    }
})
