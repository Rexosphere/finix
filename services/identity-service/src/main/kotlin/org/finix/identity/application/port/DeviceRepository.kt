package org.finix.identity.application.port

import org.finix.identity.domain.Device
import java.util.UUID

/** Outbound port for [Device] persistence. */
interface DeviceRepository {
    fun findById(id: UUID): Device?
    fun findByUserId(userId: UUID): List<Device>
    fun findByUserIdAndFingerprint(userId: UUID, fingerprint: String): Device?
    fun hasRevokedFingerprint(userId: UUID, fingerprint: String): Boolean
    fun save(device: Device): Device
}
