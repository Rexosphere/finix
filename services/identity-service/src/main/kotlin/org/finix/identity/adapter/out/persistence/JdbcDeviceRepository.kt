package org.finix.identity.adapter.out.persistence

import org.finix.identity.application.port.DeviceRepository
import org.finix.identity.domain.Device
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/** JDBC adapter over `device` (V1__identity.sql). */
@Repository
class JdbcDeviceRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : DeviceRepository {

    override fun findById(id: UUID): Device? =
        jdbc.query(SELECT_BY_ID, MapSqlParameterSource("id", id)) { rs, _ -> mapRow(rs) }.firstOrNull()

    override fun findByUserId(userId: UUID): List<Device> =
        jdbc.query(SELECT_BY_USER, MapSqlParameterSource("userId", userId)) { rs, _ -> mapRow(rs) }

    override fun findByUserIdAndFingerprint(userId: UUID, fingerprint: String): Device? {
        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("fingerprint", fingerprint)
        return jdbc.query(SELECT_BY_USER_FINGERPRINT, params) { rs, _ -> mapRow(rs) }.firstOrNull()
    }

    /**
     * Revoked rows are never deleted precisely so this question can be answered: a fingerprint
     * that was revoked before is a login-risk signal, not an absence of history.
     */
    override fun hasRevokedFingerprint(userId: UUID, fingerprint: String): Boolean {
        val params = MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("fingerprint", fingerprint)
        return jdbc.queryForObject(EXISTS_REVOKED, params, Boolean::class.java) == true
    }

    override fun save(device: Device): Device {
        val params = MapSqlParameterSource()
            .addValue("id", device.id)
            .addValue("userId", device.userId)
            .addValue("fingerprint", device.fingerprint)
            .addValue("platform", device.platform)
            .addValue("trustScore", device.trustScore)
            .addValue("lastSeenAt", Timestamp.from(device.lastSeenAt))
            .addValue("revoked", device.revoked)
        jdbc.update(UPSERT, params)
        return device
    }

    private fun mapRow(rs: ResultSet) = Device(
        id = rs.getObject("id", UUID::class.java),
        userId = rs.getObject("user_id", UUID::class.java),
        fingerprint = rs.getString("fingerprint"),
        platform = rs.getString("platform"),
        trustScore = rs.getInt("trust_score"),
        lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
        revoked = rs.getBoolean("revoked"),
    )

    private companion object {
        const val COLUMNS = "id, user_id, fingerprint, platform, trust_score, last_seen_at, revoked"

        const val SELECT_BY_ID = "SELECT $COLUMNS FROM device WHERE id = :id"
        const val SELECT_BY_USER = "SELECT $COLUMNS FROM device WHERE user_id = :userId ORDER BY last_seen_at DESC"
        const val SELECT_BY_USER_FINGERPRINT = """
            SELECT $COLUMNS FROM device
            WHERE user_id = :userId AND fingerprint = :fingerprint
            ORDER BY revoked, last_seen_at DESC
        """
        const val EXISTS_REVOKED = """
            SELECT EXISTS (
                SELECT 1 FROM device
                WHERE user_id = :userId AND fingerprint = :fingerprint AND revoked = TRUE
            )
        """

        const val UPSERT = """
            INSERT INTO device ($COLUMNS)
            VALUES (:id, :userId, :fingerprint, :platform, :trustScore, :lastSeenAt, :revoked)
            ON CONFLICT (id) DO UPDATE SET
                fingerprint  = EXCLUDED.fingerprint,
                platform     = EXCLUDED.platform,
                trust_score  = EXCLUDED.trust_score,
                last_seen_at = EXCLUDED.last_seen_at,
                revoked      = EXCLUDED.revoked
        """
    }
}
