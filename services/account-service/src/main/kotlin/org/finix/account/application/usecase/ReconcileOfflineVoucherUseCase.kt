package org.finix.account.application.usecase

import org.finix.account.application.ReconcileVoucherCommand
import org.finix.account.application.port.AccountRepository
import org.finix.account.application.port.OfflineDeviceRepository
import org.finix.account.application.port.OfflineEventPublisher
import org.finix.account.application.port.VoucherSignatureVerifier
import org.finix.account.config.OfflineProperties
import org.finix.account.domain.OfflineVoucher
import org.finix.account.domain.OfflineVoucherSigning
import org.finix.account.domain.OfflineVoucherStatus
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class ReconcileOfflineVoucherUseCase(
    private val devices: OfflineDeviceRepository,
    private val accounts: AccountRepository,
    private val verifier: VoucherSignatureVerifier,
    private val events: OfflineEventPublisher,
    private val properties: OfflineProperties,
    private val clock: Clock,
) {
    @Transactional
    fun execute(cmd: ReconcileVoucherCommand): OfflineVoucher {
        val now = Instant.now(clock)
        val device = devices.findById(cmd.deviceId)
            ?: DomainError.NotFound("OfflineDevice", cmd.deviceId).raise()

        if (device.quarantined) {
            DomainError.Forbidden(
                detail = "Device '${cmd.deviceId}' is quarantined",
                properties = mapOf("deviceId" to cmd.deviceId, "reason" to (device.quarantineReason ?: "")),
            ).raise()
        }

        val payload = OfflineVoucherSigning.payload(
            payerAccountId = cmd.payerAccountId,
            payeeAccountId = cmd.payeeAccountId,
            amountMinor = cmd.amount.minorUnits,
            currency = cmd.amount.currency.currencyCode,
            deviceId = cmd.deviceId,
            deviceSeq = cmd.deviceSeq,
            nonce = cmd.nonce,
            validUntilEpochMs = cmd.validUntil.toEpochMilli(),
        )
        val signature = Base64.getDecoder().decode(cmd.signatureBase64)
        val sigOk = verifier.verify(device.publicKeySpki, payload, signature)

        val doubleSpend = devices.nonceExists(cmd.deviceId, cmd.nonce) || cmd.deviceSeq <= device.lastDeviceSeq
        if (!sigOk || doubleSpend) {
            val reason = when {
                !sigOk -> "invalid-signature"
                devices.nonceExists(cmd.deviceId, cmd.nonce) -> "nonce-reuse"
                else -> "device-seq-gap-or-reuse"
            }
            device.quarantine(reason)
            devices.save(device)
            events.publishAnomaly(cmd.deviceId, reason, cmd.deviceSeq, cmd.nonce)
            val rejected = OfflineVoucher(
                id = UUID.randomUUID(),
                deviceId = cmd.deviceId,
                payerAccountId = cmd.payerAccountId,
                payeeAccountId = cmd.payeeAccountId,
                amount = cmd.amount,
                deviceSeq = cmd.deviceSeq,
                nonce = cmd.nonce,
                validUntil = cmd.validUntil,
                status = OfflineVoucherStatus.REJECTED,
                createdAt = now,
            )
            devices.saveVoucher(rejected)
            DomainError.Conflict(
                detail = "Offline voucher rejected: $reason (device quarantined)",
                properties = mapOf(
                    "deviceId" to cmd.deviceId,
                    "reason" to reason,
                    "deviceSeq" to cmd.deviceSeq,
                ),
            ).raise()
        }

        if (cmd.payerAccountId != device.accountId) {
            DomainError.Forbidden(
                detail = "Voucher payer does not match registered device account",
                properties = mapOf(
                    "deviceAccountId" to device.accountId.toString(),
                    "payerAccountId" to cmd.payerAccountId.toString(),
                ),
            ).raise()
        }

        device.assertAccepts(cmd.deviceSeq, cmd.amount, properties.toPolicy(), now, cmd.validUntil)

        val payer = accounts.findById(cmd.payerAccountId)
            ?: DomainError.NotFound("Account", cmd.payerAccountId.toString()).raise()
        val payee = accounts.findById(cmd.payeeAccountId)
            ?: DomainError.NotFound("Account", cmd.payeeAccountId.toString()).raise()

        payer.debit(cmd.amount)
        payee.credit(cmd.amount)
        accounts.save(payer)
        accounts.save(payee)

        devices.saveNonce(cmd.deviceId, cmd.nonce)
        device.recordSettlement(cmd.deviceSeq, cmd.amount)
        devices.save(device)

        val settled = OfflineVoucher(
            id = UUID.randomUUID(),
            deviceId = cmd.deviceId,
            payerAccountId = cmd.payerAccountId,
            payeeAccountId = cmd.payeeAccountId,
            amount = cmd.amount,
            deviceSeq = cmd.deviceSeq,
            nonce = cmd.nonce,
            validUntil = cmd.validUntil,
            status = OfflineVoucherStatus.SETTLED,
            createdAt = now,
        )
        devices.saveVoucher(settled)
        events.publishSettled(settled)
        return settled
    }
}
