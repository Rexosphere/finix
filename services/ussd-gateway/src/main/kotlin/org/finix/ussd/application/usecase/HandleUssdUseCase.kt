package org.finix.ussd.application.usecase

import org.finix.kernel.domain.Money
import org.finix.kernel.domain.lkr
import org.finix.ussd.application.port.AccountClient
import org.finix.ussd.application.port.CopyCatalog
import org.finix.ussd.application.port.TransferClient
import org.finix.ussd.application.port.UssdSessionStore
import org.finix.ussd.domain.UssdDirectory
import org.finix.ussd.domain.UssdLocale
import org.finix.ussd.domain.UssdSession
import org.springframework.stereotype.Service

/**
 * Africa's Talking USSD session machine for `*334#`.
 *
 * Input [text] is the cumulative star-delimited path (`""`, `"1"`, `"2*FINIX-SAV-…"`, …).
 * Responses are plain `CON` / `END` lines — the real telco contract, not a mock.
 */
@Service
class HandleUssdUseCase(
    private val sessions: UssdSessionStore,
    private val accounts: AccountClient,
    private val transfers: TransferClient,
    private val copy: CopyCatalog,
) {

    fun execute(sessionId: String, phoneNumber: String, text: String): String {
        val subscriber = UssdDirectory.findByPhone(phoneNumber)
        var session = sessions.load(sessionId)
            ?: UssdSession(sessionId = sessionId, phoneNumber = UssdDirectory.normalize(phoneNumber))

        if (subscriber == null) {
            sessions.clear(sessionId)
            return end(copy.notRegistered(session.locale))
        }

        val parts = text.trim().takeIf { it.isNotEmpty() }?.split('*')?.filter { it.isNotEmpty() }.orEmpty()
        val reply = when {
            parts.isEmpty() -> con(copy.welcome(session.locale) + "\n" + copy.mainMenu(session.locale))
            else -> dispatch(session, subscriber, parts).also { session = it.second }.first
        }

        if (reply.startsWith("END")) {
            sessions.clear(sessionId)
        } else {
            sessions.save(session)
        }
        return reply
    }

    private fun dispatch(
        session: UssdSession,
        subscriber: UssdDirectory.Subscriber,
        parts: List<String>,
    ): Pair<String, UssdSession> {
        val locale = session.locale
        return when (parts.first()) {
            "1" -> balance(session, subscriber)
            "2" -> sendMoney(session, subscriber, parts.drop(1))
            "3" -> miniStatement(session, subscriber)
            "4" -> end(copy.loanInfo(locale)) to session
            "5" -> language(session, parts.drop(1))
            else -> con(copy.unknown(locale) + "\n" + copy.mainMenu(locale)) to session
        }
    }

    private fun balance(
        session: UssdSession,
        subscriber: UssdDirectory.Subscriber,
    ): Pair<String, UssdSession> {
        val view = accounts.getBalance(subscriber.accountId)
            ?: return end(copy.unknown(session.locale)) to session
        return end(copy.balance(session.locale, view.available, view.held)) to session
    }

    private fun miniStatement(
        session: UssdSession,
        subscriber: UssdDirectory.Subscriber,
    ): Pair<String, UssdSession> {
        val view = accounts.getBalance(subscriber.accountId)
            ?: return end(copy.unknown(session.locale)) to session
        val lines = listOf(
            "${subscriber.accountNumber} avail ${view.available}",
            "held ${view.held}",
        )
        return end(copy.miniStatement(session.locale, lines)) to session
    }

    private fun sendMoney(
        session: UssdSession,
        subscriber: UssdDirectory.Subscriber,
        rest: List<String>,
    ): Pair<String, UssdSession> =
        when {
            rest.isEmpty() -> con(copy.sendPromptPayee(session.locale)) to session
            rest.size == 1 -> promptSendAmount(session, rest[0])
            rest.size == 2 -> promptSendConfirm(session, rest[0], rest[1])
            else -> executeSend(session, subscriber, rest)
        }

    private fun promptSendAmount(session: UssdSession, payeeRaw: String): Pair<String, UssdSession> {
        val locale = session.locale
        val payee = payeeRaw.trim()
        if (UssdDirectory.findByAccountNumber(payee) == null && UssdDirectory.findByPhone(payee) == null) {
            return end(copy.payeeNotFound(locale)) to session
        }
        return con(copy.sendPromptAmount(locale, payee)) to session.copy(pendingPayee = payee)
    }

    private fun promptSendConfirm(
        session: UssdSession,
        payeeRaw: String,
        amountRaw: String,
    ): Pair<String, UssdSession> {
        val locale = session.locale
        val payee = payeeRaw.trim()
        val amount = parseAmount(amountRaw)
            ?: return end(copy.invalidAmount(locale)) to session
        return con(copy.sendConfirm(locale, payee, amount)) to
            session.copy(pendingPayee = payee, pendingAmount = amount.toString())
    }

    private fun executeSend(
        session: UssdSession,
        subscriber: UssdDirectory.Subscriber,
        rest: List<String>,
    ): Pair<String, UssdSession> {
        val locale = session.locale
        if (rest.getOrNull(2)?.trim() != "1") {
            return end(copy.unknown(locale)) to session
        }
        val payeeRaw = rest[0].trim()
        val amount = parseAmount(rest[1])
        val payee = amount?.let {
            UssdDirectory.findByAccountNumber(payeeRaw) ?: UssdDirectory.findByPhone(payeeRaw)
        }
        val reply = when {
            amount == null -> end(copy.invalidAmount(locale))
            payee == null -> end(copy.payeeNotFound(locale))
            payee.accountId == subscriber.accountId -> end(copy.sendFail(locale, "same account"))
            else -> runCatching {
                transfers.transfer(subscriber.accountId, payee.accountId, amount)
                end(copy.sendOk(locale, amount, payee.accountNumber))
            }.getOrElse { end(copy.sendFail(locale, it.message ?: "failed")) }
        }
        return reply to session
    }

    private fun language(session: UssdSession, rest: List<String>): Pair<String, UssdSession> {
        if (rest.isEmpty()) {
            return con(copy.languageMenu(session.locale)) to session
        }
        val next = when (rest.first()) {
            "1" -> UssdLocale.EN
            "2" -> UssdLocale.SI
            "3" -> UssdLocale.TA
            else -> return end(copy.unknown(session.locale)) to session
        }
        val updated = session.copy(locale = next)
        return end(copy.languageSet(next)) to updated
    }

    private fun parseAmount(raw: String): Money? =
        runCatching {
            val normalized = raw.trim().replace(",", "")
            when {
                normalized.contains("LKR", ignoreCase = true) -> Money.parse(normalized)
                else -> normalized.lkr()
            }
        }.getOrNull()?.takeIf { it.isPositive }

    private fun con(body: String): String = "CON $body"
    private fun end(body: String): String = "END $body"
}
