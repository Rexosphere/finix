package org.finix.ussd.application.usecase

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.finix.kernel.domain.lkr
import org.finix.ussd.adapter.out.copy.TrilingualCopyCatalog
import org.finix.ussd.application.AccountBalanceView
import org.finix.ussd.application.TransferResult
import org.finix.ussd.application.port.AccountClient
import org.finix.ussd.application.port.TransferClient
import org.finix.ussd.application.port.UssdSessionStore
import org.finix.ussd.domain.UssdDirectory
import org.finix.ussd.domain.UssdLocale
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

    fun balanceView(subscriber: UssdDirectory.Subscriber = UssdDirectory.FARMER) =
        AccountBalanceView(
            accountId = subscriber.accountId,
            accountNumber = subscriber.accountNumber,
            available = "25000.00".lkr(),
            held = "0.00".lkr(),
        )

    "welcome menu on empty text" {
        val useCase = HandleUssdUseCase(memoryStore(), mockk(), mockk(), TrilingualCopyCatalog())
        val reply = useCase.execute("s1", UssdDirectory.FARMER.phone, "")
        reply shouldStartWith "CON "
        reply shouldContain "1 Balance"
    }

    "balance ends the session" {
        val accounts = mockk<AccountClient>()
        every { accounts.getBalance(UssdDirectory.FARMER.accountId) } returns balanceView()
        val useCase = HandleUssdUseCase(memoryStore(), accounts, mockk(), TrilingualCopyCatalog())
        val reply = useCase.execute("s2", UssdDirectory.FARMER.phone, "1")
        reply shouldStartWith "END "
        reply shouldContain "25000"
    }

    "balance missing account ends unknown" {
        val accounts = mockk<AccountClient>()
        every { accounts.getBalance(UssdDirectory.FARMER.accountId) } returns null
        val useCase = HandleUssdUseCase(memoryStore(), accounts, mockk(), TrilingualCopyCatalog())
        useCase.execute("s2b", UssdDirectory.FARMER.phone, "1") shouldStartWith "END "
    }

    "mini statement and loan info" {
        val accounts = mockk<AccountClient>()
        every { accounts.getBalance(UssdDirectory.FARMER.accountId) } returns balanceView()
        val useCase = HandleUssdUseCase(memoryStore(), accounts, mockk(), TrilingualCopyCatalog())
        useCase.execute("s3a", UssdDirectory.FARMER.phone, "3") shouldContain "Mini-statement"
        useCase.execute("s3b", UssdDirectory.FARMER.phone, "4") shouldContain "micro-loans"
    }

    "language menu switches locale" {
        val store = memoryStore()
        val useCase = HandleUssdUseCase(store, mockk(), mockk(), TrilingualCopyCatalog())
        useCase.execute("lang", UssdDirectory.FARMER.phone, "5") shouldContain "English"
        useCase.execute("lang", UssdDirectory.FARMER.phone, "5*2") shouldContain "Sinhala"
        useCase.execute("lang2", UssdDirectory.FARMER.phone, "5*3") shouldContain "Tamil"
        useCase.execute("lang3", UssdDirectory.FARMER.phone, "5*9") shouldContain "Invalid"
    }

    "unknown menu option redisplays menu" {
        val useCase = HandleUssdUseCase(memoryStore(), mockk(), mockk(), TrilingualCopyCatalog())
        val reply = useCase.execute("s9", UssdDirectory.FARMER.phone, "9")
        reply shouldStartWith "CON "
        reply shouldContain "1 Balance"
    }

    "send money happy path" {
        val transfers = mockk<TransferClient>()
        every {
            transfers.transfer(UssdDirectory.FARMER.accountId, UssdDirectory.SME.accountId, any())
        } returns TransferResult(UUID.randomUUID(), "COMPLETED")
        val store = memoryStore()
        val useCase = HandleUssdUseCase(store, mockk(), transfers, TrilingualCopyCatalog())

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
        done shouldContain "Sent"
        verify(exactly = 1) {
            transfers.transfer(UssdDirectory.FARMER.accountId, UssdDirectory.SME.accountId, "100.00".lkr())
        }
    }

    "send money rejects unknown payee and invalid amount" {
        val useCase = HandleUssdUseCase(memoryStore(), mockk(), mockk(), TrilingualCopyCatalog())
        useCase.execute("s5", UssdDirectory.FARMER.phone, "2*NOPE") shouldContain "Payee not found"
        useCase.execute(
            "s5b",
            UssdDirectory.FARMER.phone,
            "2*${UssdDirectory.SME.accountNumber}*abc",
        ) shouldContain "Invalid amount"
    }

    "send money cancel and same-account fail" {
        val useCase = HandleUssdUseCase(memoryStore(), mockk(), mockk(), TrilingualCopyCatalog())
        useCase.execute(
            "s6",
            UssdDirectory.FARMER.phone,
            "2*${UssdDirectory.SME.accountNumber}*10.00*2",
        ) shouldContain "Invalid"
        useCase.execute(
            "s6b",
            UssdDirectory.FARMER.phone,
            "2*${UssdDirectory.FARMER.accountNumber}*10.00*1",
        ) shouldContain "same account"
    }

    "send money by phone and transfer failure" {
        val transfers = mockk<TransferClient>()
        every {
            transfers.transfer(UssdDirectory.FARMER.accountId, UssdDirectory.SME.accountId, any())
        } throws IllegalStateException("risk blocked")
        val useCase = HandleUssdUseCase(memoryStore(), mockk(), transfers, TrilingualCopyCatalog())
        val done = useCase.execute(
            "s7",
            UssdDirectory.FARMER.phone,
            "2*${UssdDirectory.SME.phone}*25.00*1",
        )
        done shouldContain "Transfer failed"
        done shouldContain "risk blocked"
    }

    "unknown phone is rejected" {
        val useCase = HandleUssdUseCase(memoryStore(), mockk(), mockk(), TrilingualCopyCatalog())
        val reply = useCase.execute("s4", "+94770000000", "")
        reply shouldStartWith "END "
        reply shouldContain "not registered"
    }

    "session locale is reused across steps" {
        val store = memoryStore()
        store.save(
            UssdSession(
                sessionId = "persist",
                phoneNumber = UssdDirectory.FARMER.phone,
                locale = UssdLocale.SI,
            ),
        )
        val accounts = mockk<AccountClient>()
        every { accounts.getBalance(UssdDirectory.FARMER.accountId) } returns balanceView()
        val useCase = HandleUssdUseCase(store, accounts, mockk(), TrilingualCopyCatalog())
        useCase.execute("persist", UssdDirectory.FARMER.phone, "1") shouldStartWith "END "
    }
})
