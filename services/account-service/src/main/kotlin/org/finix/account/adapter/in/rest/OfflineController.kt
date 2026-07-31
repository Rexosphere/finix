package org.finix.account.adapter.`in`.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.finix.account.application.ReconcileVoucherCommand
import org.finix.account.application.usecase.ReconcileOfflineVoucherUseCase
import org.finix.account.application.usecase.RegisterOfflineDeviceUseCase
import org.finix.account.domain.OfflineDevice
import org.finix.account.domain.OfflineVoucher
import org.finix.kernel.domain.Money
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/offline")
class OfflineController(
    private val registerDevice: RegisterOfflineDeviceUseCase,
    private val reconcile: ReconcileOfflineVoucherUseCase,
) {

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody body: RegisterDeviceRequest): OfflineDeviceResponse =
        registerDevice.execute(
            deviceId = body.deviceId,
            ownerUserId = body.ownerUserId,
            accountId = body.accountId,
            publicKeySpkiBase64 = body.publicKeySpkiBase64,
        ).toResponse()

    @PostMapping("/vouchers/reconcile")
    fun reconcileVoucher(@Valid @RequestBody body: ReconcileVoucherRequest): OfflineVoucherResponse =
        reconcile.execute(
            ReconcileVoucherCommand(
                deviceId = body.deviceId,
                payerAccountId = body.payerAccountId,
                payeeAccountId = body.payeeAccountId,
                amount = body.amount,
                deviceSeq = body.deviceSeq,
                nonce = body.nonce,
                validUntil = Instant.ofEpochMilli(body.validUntilEpochMs),
                signatureBase64 = body.signatureBase64,
            ),
        ).toResponse()
}

data class RegisterDeviceRequest(
    @field:NotBlank val deviceId: String,
    @field:NotNull val ownerUserId: UUID,
    @field:NotNull val accountId: UUID,
    @field:NotBlank val publicKeySpkiBase64: String,
)

data class ReconcileVoucherRequest(
    @field:NotBlank val deviceId: String,
    @field:NotNull val payerAccountId: UUID,
    @field:NotNull val payeeAccountId: UUID,
    @field:NotNull val amount: Money,
    @field:Positive val deviceSeq: Long,
    @field:NotBlank val nonce: String,
    @field:Positive val validUntilEpochMs: Long,
    @field:NotBlank val signatureBase64: String,
)

data class OfflineDeviceResponse(
    val deviceId: String,
    val ownerUserId: UUID,
    val accountId: UUID,
    val lastDeviceSeq: Long,
    val cumulativeMinor: Long,
    val quarantined: Boolean,
)

data class OfflineVoucherResponse(
    val id: UUID,
    val deviceId: String,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: Money,
    val deviceSeq: Long,
    val nonce: String,
    val status: String,
)

private fun OfflineDevice.toResponse() = OfflineDeviceResponse(
    deviceId = deviceId,
    ownerUserId = ownerUserId,
    accountId = accountId,
    lastDeviceSeq = lastDeviceSeq,
    cumulativeMinor = cumulativeMinor,
    quarantined = quarantined,
)

private fun OfflineVoucher.toResponse() = OfflineVoucherResponse(
    id = id,
    deviceId = deviceId,
    payerAccountId = payerAccountId,
    payeeAccountId = payeeAccountId,
    amount = amount,
    deviceSeq = deviceSeq,
    nonce = nonce,
    status = status.name,
)
