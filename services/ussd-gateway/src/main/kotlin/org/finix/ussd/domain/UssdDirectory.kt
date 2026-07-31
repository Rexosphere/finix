package org.finix.ussd.domain

import java.util.UUID

/** Demo phone → account mapping for the Africa's Talking `*334#` menu. */
object UssdDirectory {

    data class Subscriber(
        val phone: String,
        val displayName: String,
        val userId: UUID,
        val accountId: UUID,
        val accountNumber: String,
    )

    val FARMER = Subscriber(
        phone = "+94771110001",
        displayName = "Farmer",
        userId = UUID.fromString("a1111111-1111-4111-8111-111111111101"),
        accountId = UUID.fromString("a2222222-2222-4222-8222-222222222201"),
        accountNumber = "FINIX-SAV-00000001",
    )

    val SME = Subscriber(
        phone = "+94771110002",
        displayName = "SME Owner",
        userId = UUID.fromString("a1111111-1111-4111-8111-111111111102"),
        accountId = UUID.fromString("a2222222-2222-4222-8222-222222222202"),
        accountNumber = "FINIX-CUR-00000002",
    )

    val ELDER = Subscriber(
        phone = "+94771110003",
        displayName = "Elder",
        userId = UUID.fromString("a1111111-1111-4111-8111-111111111103"),
        accountId = UUID.fromString("a2222222-2222-4222-8222-222222222203"),
        accountNumber = "FINIX-SAV-00000003",
    )

    private val byPhone: Map<String, Subscriber> =
        listOf(FARMER, SME, ELDER).associateBy { normalize(it.phone) }

    private val byAccountNumber: Map<String, Subscriber> =
        listOf(FARMER, SME, ELDER).associateBy { it.accountNumber }

    fun findByPhone(raw: String): Subscriber? = byPhone[normalize(raw)]

    fun findByAccountNumber(number: String): Subscriber? = byAccountNumber[number.trim().uppercase()]

    fun normalize(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.startsWith("94") && digits.length >= 11 -> "+$digits"
            digits.startsWith("0") && digits.length == 10 -> "+94${digits.drop(1)}"
            digits.length == 9 -> "+94$digits"
            else -> if (raw.startsWith("+")) raw else "+$digits"
        }
    }
}

enum class UssdLocale { EN, SI, TA }

data class UssdSession(
    val sessionId: String,
    val phoneNumber: String,
    val locale: UssdLocale = UssdLocale.EN,
    val menuStack: List<String> = emptyList(),
    val pendingPayee: String? = null,
    val pendingAmount: String? = null,
)
