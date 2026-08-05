package org.finix.vault.adapter.out.persistence

import org.finix.vault.application.port.CeremonyRepository
import org.finix.vault.domain.Ceremony
import org.finix.vault.domain.CeremonyState
import org.finix.vault.domain.CustodianId
import org.finix.vault.domain.EgressLogEntry
import org.finix.vault.domain.SealedShard
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.nio.ByteBuffer
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * JDBC adapter over `ceremony`, `sealed_shard`, `custodian_approval` and `egress_log`
 * (V1__vault.sql).
 *
 * Only ciphertext and public commitments are ever written — the Master Key exists as plaintext
 * solely inside [org.finix.vault.application.usecase.SplitMasterKeyUseCase] and the enclave.
 */
@Repository
class JdbcCeremonyRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : CeremonyRepository {

    override fun save(ceremony: Ceremony): Ceremony {
        val params = MapSqlParameterSource()
            .addValue("id", ceremony.id)
            .addValue("state", ceremony.state.name)
            .addValue("threshold", ceremony.threshold)
            .addValue("commitments", packCommitments(ceremony.commitments))
            .addValue("sealedNetworkConfig", ceremony.sealedNetworkConfig)
            .addValue("createdAt", Timestamp.from(ceremony.createdAt))
            .addValue("updatedAt", Timestamp.from(ceremony.updatedAt))
        jdbc.update(UPSERT_CEREMONY, params)
        saveApprovals(ceremony)
        return ceremony
    }

    override fun findById(id: UUID): Ceremony? =
        loadCeremony(SELECT_CEREMONY_BY_ID, MapSqlParameterSource("id", id))

    override fun findLatest(): Ceremony? =
        loadCeremony(SELECT_LATEST_CEREMONY, MapSqlParameterSource())

    override fun saveShard(shard: SealedShard): SealedShard {
        jdbc.update(UPSERT_SHARD, shardParams(shard))
        return shard
    }

    override fun findShards(ceremonyId: UUID): List<SealedShard> =
        jdbc.query(SELECT_SHARDS, MapSqlParameterSource("ceremonyId", ceremonyId)) { rs, _ ->
            SealedShard(
                id = rs.getObject("id", UUID::class.java),
                ceremonyId = rs.getObject("ceremony_id", UUID::class.java),
                custodianId = CustodianId.valueOf(rs.getString("custodian_id")),
                shareIndex = rs.getInt("share_index"),
                ciphertext = rs.getBytes("ciphertext"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }

    /**
     * Replace rather than upsert: a re-split produces a completely new share set, and leaving a
     * stale shard behind would let a reconstruct mix shares from two different Master Keys.
     */
    override fun replaceShards(ceremonyId: UUID, shardList: List<SealedShard>) {
        jdbc.update(DELETE_SHARDS, MapSqlParameterSource("ceremonyId", ceremonyId))
        shardList.forEach { jdbc.update(UPSERT_SHARD, shardParams(it)) }
    }

    override fun appendEgress(entry: EgressLogEntry): EgressLogEntry {
        val params = MapSqlParameterSource()
            .addValue("id", entry.id)
            .addValue("ceremonyId", entry.ceremonyId)
            .addValue("recordedAt", Timestamp.from(entry.recordedAt))
            .addValue("message", entry.message)
        jdbc.update(INSERT_EGRESS, params)
        return entry
    }

    override fun findEgressLog(ceremonyId: UUID): List<EgressLogEntry> =
        jdbc.query(SELECT_EGRESS, MapSqlParameterSource("ceremonyId", ceremonyId)) { rs, _ ->
            EgressLogEntry(
                id = rs.getObject("id", UUID::class.java),
                ceremonyId = rs.getObject("ceremony_id", UUID::class.java),
                recordedAt = rs.getTimestamp("recorded_at").toInstant(),
                message = rs.getString("message"),
            )
        }

    /** Children cascade from `ceremony`, so one delete wipes shards, approvals and egress. */
    override fun deleteAll() {
        jdbc.update(DELETE_ALL_CEREMONIES, MapSqlParameterSource())
    }

    private fun saveApprovals(ceremony: Ceremony) {
        ceremony.approvals.forEach { custodian ->
            val params = MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("ceremonyId", ceremony.id)
                .addValue("custodianId", custodian.name)
                .addValue("approvedAt", Timestamp.from(ceremony.updatedAt))
            jdbc.update(INSERT_APPROVAL, params)
        }
    }

    private fun shardParams(shard: SealedShard) = MapSqlParameterSource()
        .addValue("id", shard.id)
        .addValue("ceremonyId", shard.ceremonyId)
        .addValue("custodianId", shard.custodianId.name)
        .addValue("shareIndex", shard.shareIndex)
        .addValue("ciphertext", shard.ciphertext)
        .addValue("createdAt", Timestamp.from(shard.createdAt))

    private fun loadCeremony(sql: String, params: MapSqlParameterSource): Ceremony? {
        val row = jdbc.query(sql, params) { rs, _ -> mapRow(rs) }.firstOrNull() ?: return null
        return Ceremony.rehydrate(
            id = row.id,
            state = row.state,
            threshold = row.threshold,
            commitments = row.commitments,
            sealedNetworkConfig = row.sealedNetworkConfig,
            approvals = loadApprovals(row.id),
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )
    }

    private fun loadApprovals(ceremonyId: UUID): Set<CustodianId> =
        jdbc.query(SELECT_APPROVALS, MapSqlParameterSource("ceremonyId", ceremonyId)) { rs, _ ->
            CustodianId.valueOf(rs.getString("custodian_id"))
        }.toSet()

    private fun mapRow(rs: ResultSet) = CeremonyRow(
        id = rs.getObject("id", UUID::class.java),
        state = CeremonyState.valueOf(rs.getString("state")),
        threshold = rs.getInt("threshold"),
        commitments = unpackCommitments(rs.getBytes("commitments")),
        sealedNetworkConfig = rs.getBytes("sealed_network_config"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    private data class CeremonyRow(
        val id: UUID,
        val state: CeremonyState,
        val threshold: Int,
        val commitments: List<ByteArray>,
        val sealedNetworkConfig: ByteArray,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    private companion object {
        const val CEREMONY_COLUMNS =
            "id, state, threshold, commitments, sealed_network_config, created_at, updated_at"

        /**
         * `ceremony.commitments` is a single BYTEA holding the whole Feldman commitment vector,
         * so the elements are length-prefixed (4-byte big-endian count each) to survive the
         * round trip. The schema comment calls these "length-prefixed commitment points".
         */
        fun packCommitments(commitments: List<ByteArray>): ByteArray {
            val total = commitments.sumOf { Int.SIZE_BYTES + it.size }
            val buffer = ByteBuffer.allocate(total)
            commitments.forEach { buffer.putInt(it.size).put(it) }
            return buffer.array()
        }

        fun unpackCommitments(packed: ByteArray?): List<ByteArray> {
            if (packed == null || packed.isEmpty()) return emptyList()
            val buffer = ByteBuffer.wrap(packed)
            val commitments = mutableListOf<ByteArray>()
            while (buffer.remaining() >= Int.SIZE_BYTES) {
                val size = buffer.int
                if (size <= 0 || size > buffer.remaining()) break
                val element = ByteArray(size)
                buffer.get(element)
                commitments += element
            }
            return commitments
        }

        const val SELECT_CEREMONY_BY_ID = "SELECT $CEREMONY_COLUMNS FROM ceremony WHERE id = :id"
        const val SELECT_LATEST_CEREMONY =
            "SELECT $CEREMONY_COLUMNS FROM ceremony ORDER BY created_at DESC LIMIT 1"

        const val UPSERT_CEREMONY = """
            INSERT INTO ceremony (
                id, state, threshold, commitments, sealed_network_config, created_at, updated_at
            ) VALUES (
                :id, :state, :threshold, :commitments, :sealedNetworkConfig, :createdAt, :updatedAt
            )
            ON CONFLICT (id) DO UPDATE SET
                state      = EXCLUDED.state,
                updated_at = EXCLUDED.updated_at
        """

        const val DELETE_ALL_CEREMONIES = "DELETE FROM ceremony"

        const val SELECT_SHARDS = """
            SELECT id, ceremony_id, custodian_id, share_index, ciphertext, created_at
            FROM sealed_shard
            WHERE ceremony_id = :ceremonyId
            ORDER BY share_index
        """

        const val UPSERT_SHARD = """
            INSERT INTO sealed_shard (id, ceremony_id, custodian_id, share_index, ciphertext, created_at)
            VALUES (:id, :ceremonyId, :custodianId, :shareIndex, :ciphertext, :createdAt)
            ON CONFLICT (ceremony_id, custodian_id) DO UPDATE SET
                share_index = EXCLUDED.share_index,
                ciphertext  = EXCLUDED.ciphertext,
                created_at  = EXCLUDED.created_at
        """

        const val DELETE_SHARDS = "DELETE FROM sealed_shard WHERE ceremony_id = :ceremonyId"

        const val SELECT_APPROVALS =
            "SELECT custodian_id FROM custodian_approval WHERE ceremony_id = :ceremonyId"

        const val INSERT_APPROVAL = """
            INSERT INTO custodian_approval (id, ceremony_id, custodian_id, approved_at)
            VALUES (:id, :ceremonyId, :custodianId, :approvedAt)
            ON CONFLICT (ceremony_id, custodian_id) DO NOTHING
        """

        const val INSERT_EGRESS = """
            INSERT INTO egress_log (id, ceremony_id, recorded_at, message)
            VALUES (:id, :ceremonyId, :recordedAt, :message)
            ON CONFLICT (id) DO NOTHING
        """

        const val SELECT_EGRESS = """
            SELECT id, ceremony_id, recorded_at, message
            FROM egress_log
            WHERE ceremony_id = :ceremonyId
            ORDER BY recorded_at, id
        """
    }
}
