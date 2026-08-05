package org.finix.ledger.adapter.out.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import org.finix.kernel.domain.Money
import org.finix.ledger.application.LedgerCanonicalizer
import org.finix.ledger.application.port.LedgerRepository
import org.finix.ledger.domain.EntrySide
import org.finix.ledger.domain.JournalEntry
import org.finix.ledger.domain.JournalLine
import org.finix.ledger.domain.LedgerChain
import org.finix.ledger.domain.LedgerHead
import org.finix.ledger.domain.VerificationReport
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * JDBC adapter over `ledger_entry` + `ledger_line` (V1__ledger.sql).
 *
 * Every statement here is an INSERT or a SELECT. The append-only triggers would reject an
 * UPDATE anyway, but the adapter does not attempt one — the only mutation path is the
 * dev-profile [injectTamper], which goes through the privileged SQL function on purpose.
 */
@Repository
class JdbcLedgerRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
    private val canonicalizer: LedgerCanonicalizer,
) : LedgerRepository {

    override fun append(entry: JournalEntry) {
        val entryParams = MapSqlParameterSource()
            .addValue("id", entry.id)
            .addValue("transactionId", entry.transactionId)
            .addValue("sequence", entry.sequence)
            .addValue("prevHash", entry.prevHash)
            .addValue("entryHash", entry.entryHash)
            .addValue("payload", mapper.writeValueAsString(entry.payload))
            .addValue("recordedAt", Timestamp.from(entry.recordedAt))
        jdbc.update(INSERT_ENTRY, entryParams)

        entry.lines.forEach { line ->
            val lineParams = MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("entryId", entry.id)
                .addValue("accountId", line.accountId)
                .addValue("side", line.side.name)
                .addValue("amountMinor", line.amount.minorUnits)
                .addValue("currency", line.amount.currency.currencyCode)
            jdbc.update(INSERT_LINE, lineParams)
        }
    }

    override fun latestHead(): LedgerHead =
        jdbc.query(SELECT_HEAD, MapSqlParameterSource()) { rs, _ ->
            LedgerHead(latestHash = rs.getString("entry_hash").trim(), latestSequence = rs.getLong("sequence"))
        }.firstOrNull() ?: LedgerHead.GENESIS

    override fun findByTransactionId(transactionId: UUID): JournalEntry? =
        loadEntries(SELECT_BY_TRANSACTION, MapSqlParameterSource("transactionId", transactionId)).firstOrNull()

    override fun findFromSequence(fromSequence: Long, limit: Int): List<JournalEntry> {
        val params = MapSqlParameterSource()
            .addValue("fromSequence", fromSequence)
            .addValue("limit", limit)
        return loadEntries(SELECT_FROM_SEQUENCE, params)
    }

    override fun findSequenceRange(fromSequence: Long, toSequence: Long): List<JournalEntry> {
        val params = MapSqlParameterSource()
            .addValue("fromSequence", fromSequence)
            .addValue("toSequence", toSequence)
        return loadEntries(SELECT_SEQUENCE_RANGE, params)
    }

    /**
     * Verification is delegated to the pure [LedgerChain] walk rather than done in SQL: the
     * check must recompute the digest from the stored payload with the same canonicalisation
     * the writer used, which is exactly what a SQL-side check could not reproduce.
     */
    override fun verifyChain(): VerificationReport =
        LedgerChain.verify(loadEntries(SELECT_ALL, MapSqlParameterSource()), canonicalizer::bytes)

    override fun injectTamper(sequence: Long) {
        // `SELECT fn(...)` must go through query(): executeUpdate() on a result-returning
        // statement is an error in the Postgres driver.
        jdbc.query(TAMPER, MapSqlParameterSource("sequence", sequence)) { _, _ -> Unit }
    }

    private fun loadEntries(sql: String, params: MapSqlParameterSource): List<JournalEntry> {
        val headers = jdbc.query(sql, params) { rs, _ -> mapHeader(rs) }
        if (headers.isEmpty()) return emptyList()
        val lines = loadLines(headers.map { it.id })
        return headers.map { it.toEntry(lines[it.id].orEmpty()) }
    }

    private fun loadLines(entryIds: List<UUID>): Map<UUID, List<JournalLine>> =
        jdbc.query(SELECT_LINES, MapSqlParameterSource("entryIds", entryIds)) { rs, _ ->
            rs.getObject("entry_id", UUID::class.java) to JournalLine(
                accountId = rs.getObject("account_id", UUID::class.java),
                side = EntrySide.valueOf(rs.getString("side")),
                amount = Money.ofMinor(
                    rs.getLong("amount_minor"),
                    Currency.getInstance(rs.getString("currency").trim()),
                ),
            )
        }.groupBy({ it.first }, { it.second })

    private fun mapHeader(rs: ResultSet): EntryHeader {
        @Suppress("UNCHECKED_CAST")
        val payload = mapper.readValue(rs.getString("payload"), Map::class.java) as Map<String, Any?>
        return EntryHeader(
            id = rs.getObject("id", UUID::class.java),
            transactionId = rs.getObject("transaction_id", UUID::class.java),
            sequence = rs.getLong("sequence"),
            prevHash = rs.getString("prev_hash").trim(),
            entryHash = rs.getString("entry_hash").trim(),
            payload = payload,
            recordedAt = rs.getTimestamp("recorded_at").toInstant(),
        )
    }

    /** `ledger_entry` row before its lines are attached. */
    private data class EntryHeader(
        val id: UUID,
        val transactionId: UUID,
        val sequence: Long,
        val prevHash: String,
        val entryHash: String,
        val payload: Map<String, Any?>,
        val recordedAt: Instant,
    ) {
        fun toEntry(lines: List<JournalLine>): JournalEntry = JournalEntry.rehydrate(
            id = id,
            transactionId = transactionId,
            lines = lines,
            prevHash = prevHash,
            entryHash = entryHash,
            recordedAt = recordedAt,
            sequence = sequence,
            payload = payload,
        )
    }

    private companion object {
        const val ENTRY_COLUMNS =
            "id, transaction_id, sequence, prev_hash, entry_hash, payload::text AS payload, recorded_at"

        const val SELECT_HEAD = "SELECT entry_hash, sequence FROM ledger_entry ORDER BY sequence DESC LIMIT 1"

        const val SELECT_BY_TRANSACTION =
            "SELECT $ENTRY_COLUMNS FROM ledger_entry WHERE transaction_id = :transactionId"

        const val SELECT_FROM_SEQUENCE = """
            SELECT $ENTRY_COLUMNS FROM ledger_entry
            WHERE sequence >= :fromSequence
            ORDER BY sequence
            LIMIT :limit
        """

        const val SELECT_SEQUENCE_RANGE = """
            SELECT $ENTRY_COLUMNS FROM ledger_entry
            WHERE sequence BETWEEN :fromSequence AND :toSequence
            ORDER BY sequence
        """

        const val SELECT_ALL = "SELECT $ENTRY_COLUMNS FROM ledger_entry ORDER BY sequence"

        const val SELECT_LINES = """
            SELECT entry_id, account_id, side, amount_minor, currency
            FROM ledger_line
            WHERE entry_id IN (:entryIds)
            ORDER BY account_id, side
        """

        const val INSERT_ENTRY = """
            INSERT INTO ledger_entry (id, transaction_id, sequence, prev_hash, entry_hash, payload, recorded_at)
            VALUES (:id, :transactionId, :sequence, :prevHash, :entryHash, cast(:payload AS jsonb), :recordedAt)
        """

        const val INSERT_LINE = """
            INSERT INTO ledger_line (id, entry_id, account_id, side, amount_minor, currency)
            VALUES (:id, :entryId, :accountId, :side, :amountMinor, :currency)
        """

        const val TAMPER = "SELECT finix_dev_tamper_entry_hash(:sequence)"
    }
}
