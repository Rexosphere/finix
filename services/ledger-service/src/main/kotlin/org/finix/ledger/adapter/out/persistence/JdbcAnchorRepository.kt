package org.finix.ledger.adapter.out.persistence

import org.finix.ledger.application.port.AnchorRepository
import org.finix.ledger.domain.LedgerAnchor
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/** JDBC adapter over `ledger_anchor` (V2__anchor.sql). */
@Repository
class JdbcAnchorRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : AnchorRepository {

    override fun save(anchor: LedgerAnchor): LedgerAnchor {
        val params = MapSqlParameterSource()
            .addValue("id", anchor.id)
            .addValue("windowStartSeq", anchor.windowStartSeq)
            .addValue("windowEndSeq", anchor.windowEndSeq)
            .addValue("merkleRoot", anchor.merkleRoot)
            .addValue("entryCount", anchor.entryCount)
            .addValue("signature", anchor.signature)
            .addValue("publicKey", anchor.publicKey)
            .addValue("anchoredAt", Timestamp.from(anchor.anchoredAt))
        jdbc.update(INSERT, params)
        return anchor
    }

    override fun findLatest(): LedgerAnchor? =
        jdbc.query(SELECT_LATEST, MapSqlParameterSource()) { rs, _ -> mapRow(rs) }.firstOrNull()

    /** The anchor whose window contains [sequence]; null while the tip is not yet anchored. */
    override fun findCovering(sequence: Long): LedgerAnchor? =
        jdbc.query(SELECT_COVERING, MapSqlParameterSource("sequence", sequence)) { rs, _ -> mapRow(rs) }
            .firstOrNull()

    override fun findAll(): List<LedgerAnchor> =
        jdbc.query(SELECT_ALL, MapSqlParameterSource()) { rs, _ -> mapRow(rs) }

    private fun mapRow(rs: ResultSet) = LedgerAnchor(
        id = rs.getObject("id", UUID::class.java),
        windowStartSeq = rs.getLong("window_start_seq"),
        windowEndSeq = rs.getLong("window_end_seq"),
        merkleRoot = rs.getString("merkle_root").trim(),
        entryCount = rs.getInt("entry_count"),
        signature = rs.getBytes("signature"),
        publicKey = rs.getBytes("public_key"),
        anchoredAt = rs.getTimestamp("anchored_at").toInstant(),
    )

    private companion object {
        const val COLUMNS = """
            id, window_start_seq, window_end_seq, merkle_root, entry_count,
            signature, public_key, anchored_at
        """

        const val SELECT_LATEST =
            "SELECT $COLUMNS FROM ledger_anchor ORDER BY window_end_seq DESC LIMIT 1"

        const val SELECT_COVERING = """
            SELECT $COLUMNS FROM ledger_anchor
            WHERE window_start_seq <= :sequence AND window_end_seq >= :sequence
            ORDER BY window_end_seq DESC
            LIMIT 1
        """

        const val SELECT_ALL = "SELECT $COLUMNS FROM ledger_anchor ORDER BY window_end_seq"

        const val INSERT = """
            INSERT INTO ledger_anchor (
                id, window_start_seq, window_end_seq, merkle_root, entry_count,
                signature, public_key, anchored_at
            ) VALUES (
                :id, :windowStartSeq, :windowEndSeq, :merkleRoot, :entryCount,
                :signature, :publicKey, :anchoredAt
            )
            ON CONFLICT (window_start_seq, window_end_seq) DO NOTHING
        """
    }
}
