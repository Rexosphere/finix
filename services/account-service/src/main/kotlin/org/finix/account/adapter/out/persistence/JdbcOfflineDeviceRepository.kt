package org.finix.account.adapter.out.persistence

import org.finix.account.application.port.OfflineDeviceRepository
import org.finix.account.domain.OfflineDevice
import org.finix.account.domain.OfflineVoucher
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/** JDBC adapter over `offline_device`, `offline_nonce` and `offline_voucher` (V2__offline_voucher.sql). */
@Repository
class JdbcOfflineDeviceRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : OfflineDeviceRepository {

    override fun findById(deviceId: String): OfflineDevice? =
        jdbc.query(SELECT_DEVICE, MapSqlParameterSource("deviceId", deviceId)) { rs, _ -> mapDevice(rs) }
            .firstOrNull()

    override fun save(device: OfflineDevice): OfflineDevice {
        val params = MapSqlParameterSource()
            .addValue("deviceId", device.deviceId)
            .addValue("ownerUserId", device.ownerUserId)
            .addValue("accountId", device.accountId)
            .addValue("publicKeySpki", device.publicKeySpki)
            .addValue("lastDeviceSeq", device.lastDeviceSeq)
            .addValue("cumulativeMinor", device.cumulativeMinor)
            .addValue("quarantined", device.quarantined)
            .addValue("quarantineReason", device.quarantineReason)
        jdbc.update(UPSERT_DEVICE, params)
        return device
    }

    override fun nonceExists(deviceId: String, nonce: String): Boolean {
        val params = MapSqlParameterSource()
            .addValue("deviceId", deviceId)
            .addValue("nonce", nonce)
        return jdbc.queryForObject(NONCE_EXISTS, params, Boolean::class.java) == true
    }

    /**
     * `DO NOTHING` rather than an upsert: a nonce that is already present is exactly the
     * double-spend the table exists to detect, and [nonceExists] is what reports it.
     */
    override fun saveNonce(deviceId: String, nonce: String) {
        val params = MapSqlParameterSource()
            .addValue("deviceId", deviceId)
            .addValue("nonce", nonce)
        jdbc.update(INSERT_NONCE, params)
    }

    override fun saveVoucher(voucher: OfflineVoucher): OfflineVoucher {
        val params = MapSqlParameterSource()
            .addValue("id", voucher.id)
            .addValue("deviceId", voucher.deviceId)
            .addValue("payerAccountId", voucher.payerAccountId)
            .addValue("payeeAccountId", voucher.payeeAccountId)
            .addValue("amountMinor", voucher.amount.minorUnits)
            .addValue("currency", voucher.amount.currency.currencyCode)
            .addValue("deviceSeq", voucher.deviceSeq)
            .addValue("nonce", voucher.nonce)
            .addValue("validUntil", Timestamp.from(voucher.validUntil))
            .addValue("status", voucher.status.name)
            .addValue("createdAt", Timestamp.from(voucher.createdAt))
        jdbc.update(INSERT_VOUCHER, params)
        return voucher
    }

    private fun mapDevice(rs: ResultSet) = OfflineDevice(
        deviceId = rs.getString("device_id"),
        ownerUserId = rs.getObject("owner_user_id", UUID::class.java),
        accountId = rs.getObject("account_id", UUID::class.java),
        publicKeySpki = rs.getBytes("public_key_spki"),
        lastDeviceSeq = rs.getLong("last_device_seq"),
        cumulativeMinor = rs.getLong("cumulative_minor"),
        quarantined = rs.getBoolean("quarantined"),
        quarantineReason = rs.getString("quarantine_reason"),
    )

    private companion object {
        const val SELECT_DEVICE = """
            SELECT device_id, owner_user_id, account_id, public_key_spki,
                   last_device_seq, cumulative_minor, quarantined, quarantine_reason
            FROM offline_device
            WHERE device_id = :deviceId
        """

        const val UPSERT_DEVICE = """
            INSERT INTO offline_device (
                device_id, owner_user_id, account_id, public_key_spki,
                last_device_seq, cumulative_minor, quarantined, quarantine_reason, created_at, updated_at
            ) VALUES (
                :deviceId, :ownerUserId, :accountId, :publicKeySpki,
                :lastDeviceSeq, :cumulativeMinor, :quarantined, :quarantineReason, now(), now()
            )
            ON CONFLICT (device_id) DO UPDATE SET
                last_device_seq   = EXCLUDED.last_device_seq,
                cumulative_minor  = EXCLUDED.cumulative_minor,
                quarantined       = EXCLUDED.quarantined,
                quarantine_reason = EXCLUDED.quarantine_reason,
                updated_at        = now()
        """

        const val NONCE_EXISTS = """
            SELECT EXISTS (SELECT 1 FROM offline_nonce WHERE device_id = :deviceId AND nonce = :nonce)
        """

        const val INSERT_NONCE = """
            INSERT INTO offline_nonce (device_id, nonce, seen_at)
            VALUES (:deviceId, :nonce, now())
            ON CONFLICT (device_id, nonce) DO NOTHING
        """

        const val INSERT_VOUCHER = """
            INSERT INTO offline_voucher (
                id, device_id, payer_account_id, payee_account_id, amount_minor, currency,
                device_seq, nonce, valid_until, status, created_at
            ) VALUES (
                :id, :deviceId, :payerAccountId, :payeeAccountId, :amountMinor, :currency,
                :deviceSeq, :nonce, :validUntil, :status, :createdAt
            )
            ON CONFLICT DO NOTHING
        """
    }
}
