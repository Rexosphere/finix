package org.finix.account.domain

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.Money
import org.finix.kernel.domain.domainRequire
import java.time.Instant
import java.util.UUID

enum class OfflineVoucherStatus { SETTLED, REJECTED }

/**
 * A registered offline-capable device. Tracks monotonic [lastDeviceSeq] and cumulative spend
 * for BIS Polaris-style limits; [quarantined] blocks further reconciliation after double-spend.
 */
class OfflineDevice(
    val deviceId: String,
    val ownerUserId: UUID,
    val accountId: UUID,
    val publicKeySpki: ByteArray,
    lastDeviceSeq: Long = 0,
    cumulativeMinor: Long = 0,
    quarantined: Boolean = false,
    quarantineReason: String? = null,
) {
    var lastDeviceSeq: Long = lastDeviceSeq
        private set
    var cumulativeMinor: Long = cumulativeMinor
        private set
    var quarantined: Boolean = quarantined
        private set
    var quarantineReason: String? = quarantineReason
        private set

    fun assertAccepts(seq: Long, amount: Money, policy: OfflinePolicy, now: Instant, validUntil: Instant) {
        domainRequire(!quarantined) {
            DomainError.Forbidden(
                detail = "Device '$deviceId' is quarantined",
                properties = mapOf("deviceId" to deviceId, "reason" to (quarantineReason ?: "")),
            )
        }
        domainRequire(validUntil.isAfter(now)) {
            DomainError.Invalid(
                detail = "Voucher expired at $validUntil",
                properties = mapOf("validUntil" to validUntil.toString()),
            )
        }
        domainRequire(seq > lastDeviceSeq) {
            DomainError.Conflict(
                detail = "deviceSeq $seq is not strictly greater than last $lastDeviceSeq",
                properties = mapOf(
                    "deviceId" to deviceId,
                    "deviceSeq" to seq,
                    "lastDeviceSeq" to lastDeviceSeq,
                ),
            )
        }
        domainRequire(amount.minorUnits <= policy.maxPerTxMinor) {
            DomainError.LimitExceeded(
                limit = "max-per-tx",
                detail = "Amount $amount exceeds per-transaction offline limit",
                properties = mapOf("maxPerTxMinor" to policy.maxPerTxMinor),
            )
        }
        domainRequire(cumulativeMinor + amount.minorUnits <= policy.maxCumulativeMinor) {
            DomainError.LimitExceeded(
                limit = "max-cumulative",
                detail = "Cumulative offline spend would exceed Polaris limit",
                properties = mapOf(
                    "cumulativeMinor" to cumulativeMinor,
                    "maxCumulativeMinor" to policy.maxCumulativeMinor,
                ),
            )
        }
    }

    fun recordSettlement(seq: Long, amount: Money) {
        lastDeviceSeq = seq
        cumulativeMinor += amount.minorUnits
    }

    fun quarantine(reason: String) {
        quarantined = true
        quarantineReason = reason
    }
}

data class OfflinePolicy(
    val maxDurationHours: Long = 72,
    val maxPerTxMinor: Long = 500_000,
    val maxCumulativeMinor: Long = 2_500_000,
    val maxQueued: Int = 20,
    val reconciliationWindowHours: Long = 168,
)

data class OfflineVoucher(
    val id: UUID,
    val deviceId: String,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: Money,
    val deviceSeq: Long,
    val nonce: String,
    val validUntil: Instant,
    val status: OfflineVoucherStatus,
    val createdAt: Instant,
)

/** Canonical bytes the device signs with its non-extractable WebCrypto key. */
object OfflineVoucherSigning {
    fun payload(
        payerAccountId: UUID,
        payeeAccountId: UUID,
        amountMinor: Long,
        currency: String,
        deviceId: String,
        deviceSeq: Long,
        nonce: String,
        validUntilEpochMs: Long,
    ): ByteArray =
        listOf(
            payerAccountId.toString(),
            payeeAccountId.toString(),
            amountMinor.toString(),
            currency,
            deviceId,
            deviceSeq.toString(),
            nonce,
            validUntilEpochMs.toString(),
        ).joinToString("|").toByteArray(Charsets.UTF_8)
}
