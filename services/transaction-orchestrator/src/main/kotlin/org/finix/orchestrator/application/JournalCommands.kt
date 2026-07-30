package org.finix.orchestrator.application

import org.finix.kernel.domain.Money
import java.util.UUID

enum class JournalSide {
    DEBIT,
    CREDIT,
}

data class JournalLineCommand(
    val accountId: UUID,
    val side: JournalSide,
    val amount: Money,
)
