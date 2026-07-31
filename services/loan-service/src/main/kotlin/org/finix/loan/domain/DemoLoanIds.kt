package org.finix.loan.domain

import org.finix.kernel.domain.Money
import java.util.UUID

/**
 * Stable demo identities shared with account-service [DemoAccounts] for judge walkthroughs.
 */
object DemoLoanIds {
    val SME_USER_ID: UUID = UUID.fromString("a1111111-1111-4111-8111-111111111102")
    val SME_ACCOUNT_ID: UUID = UUID.fromString("a2222222-2222-4222-8222-222222222202")
}
