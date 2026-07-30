package org.finix.orchestrator.application.port

import org.finix.kernel.domain.Money
import java.util.UUID

/**
 * Downstream account-service port used by the transfer saga.
 *
 * Amounts travel as [Money] and are serialised to the canonical `"LKR 100.00"` wire form by the
 * HTTP adapter — the application layer never sees HTTP.
 */
interface AccountClient {
    fun reserve(accountId: UUID, amount: Money, holdId: UUID)
    fun commitHold(accountId: UUID, holdId: UUID)
    fun releaseHold(accountId: UUID, holdId: UUID)
    fun credit(accountId: UUID, amount: Money, reference: String)
}
