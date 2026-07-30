package org.finix.account.domain

import org.finix.kernel.domain.Money
import org.finix.kernel.domain.lkr
import java.util.UUID

/**
 * Stable demo identities for orchestrator scripts and judge walkthroughs.
 *
 * User ids are shared with identity-service seed personas; account ids and numbers are owned
 * here so a transfer demo never depends on looking up freshly generated UUIDs.
 */
object DemoAccounts {

    val FARMER_USER_ID: UUID = UUID.fromString("a1111111-1111-4111-8111-111111111101")
    val SME_USER_ID: UUID = UUID.fromString("a1111111-1111-4111-8111-111111111102")
    val ELDER_USER_ID: UUID = UUID.fromString("a1111111-1111-4111-8111-111111111103")

    val FARMER_ACCOUNT_ID: UUID = UUID.fromString("a2222222-2222-4222-8222-222222222201")
    val SME_ACCOUNT_ID: UUID = UUID.fromString("a2222222-2222-4222-8222-222222222202")
    val ELDER_ACCOUNT_ID: UUID = UUID.fromString("a2222222-2222-4222-8222-222222222203")

    const val FARMER_ACCOUNT_NUMBER: String = "FINIX-SAV-00000001"
    const val SME_ACCOUNT_NUMBER: String = "FINIX-CUR-00000002"
    const val ELDER_ACCOUNT_NUMBER: String = "FINIX-SAV-00000003"

    /** Farmer savings — LKR 25,000.00 */
    val FARMER_OPENING_BALANCE: Money = "25000.00".lkr()

    /** SME current — LKR 150,000.00 */
    val SME_OPENING_BALANCE: Money = "150000.00".lkr()

    /** Elder savings — LKR 80,000.00 */
    val ELDER_OPENING_BALANCE: Money = "80000.00".lkr()

    data class SeedSpec(
        val accountId: UUID,
        val ownerUserId: UUID,
        val accountNumber: String,
        val type: AccountType,
        val openingBalance: Money,
    )

    val ALL: List<SeedSpec> = listOf(
        SeedSpec(
            accountId = FARMER_ACCOUNT_ID,
            ownerUserId = FARMER_USER_ID,
            accountNumber = FARMER_ACCOUNT_NUMBER,
            type = AccountType.SAVINGS,
            openingBalance = FARMER_OPENING_BALANCE,
        ),
        SeedSpec(
            accountId = SME_ACCOUNT_ID,
            ownerUserId = SME_USER_ID,
            accountNumber = SME_ACCOUNT_NUMBER,
            type = AccountType.CURRENT,
            openingBalance = SME_OPENING_BALANCE,
        ),
        SeedSpec(
            accountId = ELDER_ACCOUNT_ID,
            ownerUserId = ELDER_USER_ID,
            accountNumber = ELDER_ACCOUNT_NUMBER,
            type = AccountType.SAVINGS,
            openingBalance = ELDER_OPENING_BALANCE,
        ),
    )
}
