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
            digits.startsWith(COUNTRY_CODE) && digits.length >= INTL_MIN_DIGITS -> "+$digits"
            digits.startsWith("0") && digits.length == LOCAL_WITH_TRUNK_DIGITS ->
                "+$COUNTRY_CODE${digits.drop(1)}"
            digits.length == LOCAL_WITHOUT_TRUNK_DIGITS -> "+$COUNTRY_CODE$digits"
            else -> if (raw.startsWith("+")) raw else "+$digits"
        }
    }

    private const val COUNTRY_CODE = "94"
    private const val INTL_MIN_DIGITS = 11
    private const val LOCAL_WITH_TRUNK_DIGITS = 10
    private const val LOCAL_WITHOUT_TRUNK_DIGITS = 9
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
