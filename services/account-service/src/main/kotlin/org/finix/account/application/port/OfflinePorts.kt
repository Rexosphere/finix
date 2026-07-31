package org.finix.account.application.port

import org.finix.account.domain.OfflineDevice
import org.finix.account.domain.OfflineVoucher

interface OfflineDeviceRepository {
    fun findById(deviceId: String): OfflineDevice?
    fun save(device: OfflineDevice): OfflineDevice
    fun nonceExists(deviceId: String, nonce: String): Boolean
    fun saveNonce(deviceId: String, nonce: String)
    fun saveVoucher(voucher: OfflineVoucher): OfflineVoucher
}

interface OfflineEventPublisher {
    fun publishSettled(voucher: OfflineVoucher)
    fun publishAnomaly(deviceId: String, reason: String, deviceSeq: Long?, nonce: String?)
}

interface VoucherSignatureVerifier {
    fun verify(publicKeySpki: ByteArray, payload: ByteArray, signatureDer: ByteArray): Boolean
}
