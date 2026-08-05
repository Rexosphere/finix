package org.finix.identity.adapter.out.persistence

import org.finix.identity.application.port.UserRepository
import org.finix.identity.domain.KycTier
import org.finix.identity.domain.UserProfile
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/** JDBC adapter over `user_profile` (V1__identity.sql). */
@Repository
class JdbcUserRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : UserRepository {

    override fun findById(id: UUID): UserProfile? = findOne(SELECT_BY_ID, "id", id)

    override fun findByKeycloakUserId(keycloakUserId: String): UserProfile? =
        findOne(SELECT_BY_KEYCLOAK_ID, "keycloakUserId", keycloakUserId)

    override fun findByEmail(email: String): UserProfile? = findOne(SELECT_BY_EMAIL, "email", email)

    /**
     * Upsert rather than insert-or-update: the seed use case is re-run on every demo boot and
     * must converge on the same rows instead of failing on the unique keycloak/email indexes.
     */
    override fun save(profile: UserProfile): UserProfile {
        val params = MapSqlParameterSource()
            .addValue("id", profile.id)
            .addValue("keycloakUserId", profile.keycloakUserId)
            .addValue("email", profile.email)
            .addValue("displayName", profile.displayName)
            .addValue("nic", profile.nic)
            .addValue("locale", profile.locale)
            .addValue("kycTier", profile.kycTier.name)
            .addValue("createdAt", Timestamp.from(profile.createdAt))
        jdbc.update(UPSERT, params)
        return profile
    }

    private fun findOne(sql: String, param: String, value: Any): UserProfile? =
        jdbc.query(sql, MapSqlParameterSource(param, value)) { rs, _ -> mapRow(rs) }.firstOrNull()

    private fun mapRow(rs: ResultSet) = UserProfile(
        id = rs.getObject("id", UUID::class.java),
        keycloakUserId = rs.getString("keycloak_user_id"),
        email = rs.getString("email"),
        displayName = rs.getString("display_name"),
        nic = rs.getString("nic"),
        locale = rs.getString("locale"),
        kycTier = KycTier.valueOf(rs.getString("kyc_tier")),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )

    private companion object {
        const val COLUMNS = "id, keycloak_user_id, email, display_name, nic, locale, kyc_tier, created_at"

        const val SELECT_BY_ID = "SELECT $COLUMNS FROM user_profile WHERE id = :id"
        const val SELECT_BY_KEYCLOAK_ID = "SELECT $COLUMNS FROM user_profile WHERE keycloak_user_id = :keycloakUserId"
        const val SELECT_BY_EMAIL = "SELECT $COLUMNS FROM user_profile WHERE email = :email"

        const val UPSERT = """
            INSERT INTO user_profile ($COLUMNS)
            VALUES (:id, :keycloakUserId, :email, :displayName, :nic, :locale, :kycTier, :createdAt)
            ON CONFLICT (id) DO UPDATE SET
                keycloak_user_id = EXCLUDED.keycloak_user_id,
                email            = EXCLUDED.email,
                display_name     = EXCLUDED.display_name,
                nic              = EXCLUDED.nic,
                locale           = EXCLUDED.locale,
                kyc_tier         = EXCLUDED.kyc_tier
        """
    }
}
