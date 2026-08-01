package org.finix.ussd.adapter.out.copy

import org.finix.kernel.domain.Money
import org.finix.ussd.application.port.CopyCatalog
import org.finix.ussd.domain.UssdLocale
import org.springframework.stereotype.Component

/** USSD menu copy in English, Sinhala, and Tamil for the `*334#` demo flow. */
@Component
class TrilingualCopyCatalog : CopyCatalog {

    override fun welcome(locale: UssdLocale): String =
        when (locale) {
            UssdLocale.EN -> "FINIX *334#"
            UssdLocale.SI -> "FINIX *334#"
            UssdLocale.TA -> "FINIX *334#"
        }

    override fun mainMenu(locale: UssdLocale): String =
        when (locale) {
            UssdLocale.EN ->
                "1 Balance\n2 Send Money\n3 Mini-statement\n4 Loan\n5 Language"
            UssdLocale.SI ->
                "1 Balance\n2 Send Money\n3 Mini-statement\n4 Loan\n5 Language"
            UssdLocale.TA ->
                "1 Balance\n2 Send Money\n3 Mini-statement\n4 Loan\n5 Language"
        }

    override fun balance(locale: UssdLocale, available: Money, held: Money): String =
        when (locale) {
            UssdLocale.EN -> "Available $available\nHeld $held"
            UssdLocale.SI -> "Available $available\nHeld $held"
            UssdLocale.TA -> "Available $available\nHeld $held"
        }

    override fun sendPromptPayee(locale: UssdLocale): String =
        when (locale) {
            UssdLocale.EN -> "Enter payee account or phone:"
            UssdLocale.SI -> "Enter payee account or phone:"
            UssdLocale.TA -> "Enter payee account or phone:"
        }

    override fun sendPromptAmount(locale: UssdLocale, payee: String): String =
        when (locale) {
            UssdLocale.EN -> "Amount to $payee (LKR):"
            UssdLocale.SI -> "Amount to $payee (LKR):"
            UssdLocale.TA -> "Amount to $payee (LKR):"
        }

    override fun sendConfirm(locale: UssdLocale, payee: String, amount: Money): String =
        when (locale) {
            UssdLocale.EN -> "Send $amount to $payee?\n1 Confirm\n2 Cancel"
            UssdLocale.SI -> "Send $amount to $payee?\n1 Confirm\n2 Cancel"
            UssdLocale.TA -> "Send $amount to $payee?\n1 Confirm\n2 Cancel"
        }

    override fun sendOk(locale: UssdLocale, amount: Money, payee: String): String =
        when (locale) {
            UssdLocale.EN -> "Sent $amount to $payee"
            UssdLocale.SI -> "Sent $amount to $payee"
            UssdLocale.TA -> "Sent $amount to $payee"
        }

    override fun sendFail(locale: UssdLocale, reason: String): String =
        when (locale) {
            UssdLocale.EN -> "Transfer failed: $reason"
            UssdLocale.SI -> "Transfer failed: $reason"
            UssdLocale.TA -> "Transfer failed: $reason"
        }

    override fun miniStatement(locale: UssdLocale, lines: List<String>): String {
        val body = lines.joinToString("\n")
        return when (locale) {
            UssdLocale.EN -> "Mini-statement\n$body"
            UssdLocale.SI -> "Mini-statement\n$body"
            UssdLocale.TA -> "Mini-statement\n$body"
        }
    }

    override fun loanInfo(locale: UssdLocale): String =
        when (locale) {
            UssdLocale.EN -> "SME micro-loans: dial web or visit branch."
            UssdLocale.SI -> "SME micro-loans: dial web or visit branch."
            UssdLocale.TA -> "SME micro-loans: dial web or visit branch."
        }

    override fun languageMenu(locale: UssdLocale): String =
        when (locale) {
            UssdLocale.EN, UssdLocale.SI, UssdLocale.TA -> "1 English\n2 Sinhala\n3 Tamil"
        }

    override fun languageSet(locale: UssdLocale): String =
        when (locale) {
            UssdLocale.EN -> "Language set to English"
            UssdLocale.SI -> "Language set to Sinhala"
            UssdLocale.TA -> "Language set to Tamil"
        }

    override fun unknown(locale: UssdLocale): String =
        when (locale) {
            UssdLocale.EN -> "Invalid option."
            UssdLocale.SI -> "Invalid option."
            UssdLocale.TA -> "Invalid option."
        }

    override fun notRegistered(locale: UssdLocale): String =
        when (locale) {
            UssdLocale.EN -> "Phone not registered on FINIX."
            UssdLocale.SI -> "Phone not registered on FINIX."
            UssdLocale.TA -> "Phone not registered on FINIX."
        }

    override fun invalidAmount(locale: UssdLocale): String =
        when (locale) {
            UssdLocale.EN -> "Invalid amount."
            UssdLocale.SI -> "Invalid amount."
            UssdLocale.TA -> "Invalid amount."
        }

    override fun payeeNotFound(locale: UssdLocale): String =
        when (locale) {
            UssdLocale.EN -> "Payee not found."
            UssdLocale.SI -> "Payee not found."
            UssdLocale.TA -> "Payee not found."
        }
}
