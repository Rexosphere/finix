package org.finix.ussd.application.port

import org.finix.kernel.domain.Money
import org.finix.ussd.application.AccountBalanceView
import org.finix.ussd.application.TransferResult
import org.finix.ussd.domain.UssdLocale
import org.finix.ussd.domain.UssdSession
import java.util.UUID

interface UssdSessionStore {
    fun load(sessionId: String): UssdSession?
    fun save(session: UssdSession)
    fun clear(sessionId: String)
}

interface AccountClient {
    fun getBalance(accountId: UUID): AccountBalanceView?
}

interface TransferClient {
    fun transfer(fromAccountId: UUID, toAccountId: UUID, amount: Money): TransferResult
}

interface CopyCatalog {
    fun welcome(locale: UssdLocale): String
    fun mainMenu(locale: UssdLocale): String
    fun balance(locale: UssdLocale, available: Money, held: Money): String
    fun sendPromptPayee(locale: UssdLocale): String
    fun sendPromptAmount(locale: UssdLocale, payee: String): String
    fun sendConfirm(locale: UssdLocale, payee: String, amount: Money): String
    fun sendOk(locale: UssdLocale, amount: Money, payee: String): String
    fun sendFail(locale: UssdLocale, reason: String): String
    fun miniStatement(locale: UssdLocale, lines: List<String>): String
    fun loanInfo(locale: UssdLocale): String
    fun languageMenu(locale: UssdLocale): String
    fun languageSet(locale: UssdLocale): String
    fun unknown(locale: UssdLocale): String
    fun notRegistered(locale: UssdLocale): String
    fun invalidAmount(locale: UssdLocale): String
    fun payeeNotFound(locale: UssdLocale): String
}
