package org.finix.account.domain

import org.finix.kernel.domain.Money
import java.time.Instant
import java.util.UUID

/**
 * A reservation of available funds against a future debit (transfer saga step 1).
 *
 * [status] is part of the aggregate so persistence can round-trip OPEN/COMMITTED/RELEASED without
 * inventing a second model — only [HoldStatus.OPEN] holds contribute to [Account.heldBalance].
 */
data class Hold(
    val id: UUID,
    val amount: Money,
    val createdAt: Instant,
    val status: HoldStatus = HoldStatus.OPEN,
)
