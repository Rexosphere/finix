package org.finix.account.application.usecase

import org.finix.account.application.port.AccountRepository
import org.finix.account.application.port.OfflineDeviceRepository
import org.finix.account.domain.OfflineDevice
import org.finix.kernel.domain.DomainError
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Base64
import java.util.UUID

@Service
class RegisterOfflineDeviceUseCase(
    private val devices: OfflineDeviceRepository,
    private val accounts: AccountRepository,
) {
    @Transactional
    fun execute(
        deviceId: String,
        ownerUserId: UUID,
        accountId: UUID,
        publicKeySpkiBase64: String,
    ): OfflineDevice {
        devices.findById(deviceId)?.let { return it }
        val account = accounts.findById(accountId)
            ?: DomainError.NotFound("Account", accountId.toString()).raise()
        if (account.ownerUserId != ownerUserId) {
            DomainError.Forbidden(
                detail = "Account does not belong to owner",
                properties = mapOf("accountId" to accountId.toString(), "ownerUserId" to ownerUserId.toString()),
            ).raise()
        }
        val spki = Base64.getDecoder().decode(publicKeySpkiBase64)
        val device = OfflineDevice(
            deviceId = deviceId,
            ownerUserId = ownerUserId,
            accountId = accountId,
            publicKeySpki = spki,
        )
        return devices.save(device)
    }
}
