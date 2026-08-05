package org.finix.account.adapter.out.persistence

import org.finix.account.application.port.AccountRepository
import org.finix.account.domain.Account
import org.finix.account.domain.AccountStatus
import org.finix.account.domain.AccountType
import org.finix.account.domain.Hold
import org.finix.account.domain.HoldStatus
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.Money
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.Currency
import java.util.UUID

/**
 * JDBC adapter over `account` + `account_hold` (V1__account.sql).
 *
 * Holds are written as part of the same aggregate rather than through their own repository:
 * the domain treats reserve/commit/release as one consistency boundary, and splitting the
 * writes would let a balance change commit without the hold that justifies it.
 */
@Repository
class JdbcAccountRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : AccountRepository {

    override fun findById(id: UUID): Account? =
        loadAll(SELECT_BY_ID, MapSqlParameterSource("id", id)).firstOrNull()

    override fun findByOwner(ownerUserId: UUID): List<Account> =
        loadAll(SELECT_BY_OWNER, MapSqlParameterSource("ownerUserId", ownerUserId))

    override fun findByAccountNumber(accountNumber: String): Account? =
        loadAll(SELECT_BY_NUMBER, MapSqlParameterSource("accountNumber", accountNumber)).firstOrNull()

    /**
     * Writes the aggregate under optimistic locking. The `UPDATE ... WHERE version = :version`
     * is the whole point: two concurrent saga steps against the same account must not both
     * succeed on a balance they each read before the other wrote.
     */
    override fun save(account: Account): Account {
        val updated = jdbc.update(UPDATE_ACCOUNT, accountParams(account).addValue("nextVersion", account.version + 1))
        val persistedVersion = if (updated > 0) {
            account.version + 1
        } else {
            insertOrFail(account)
            account.version
        }
        saveHolds(account)
        return account.withVersion(persistedVersion)
    }

    private fun insertOrFail(account: Account) {
        val inserted = jdbc.update(INSERT_ACCOUNT, accountParams(account))
        if (inserted == 0) {
            // The row exists but carried a different version — someone else wrote first.
            DomainError.ConcurrentModification("Account", account.id.toString()).raise()
        }
    }

    private fun saveHolds(account: Account) {
        account.holds.forEach { hold ->
            val params = MapSqlParameterSource()
                .addValue("id", hold.id)
                .addValue("accountId", account.id)
                .addValue("amountMinor", hold.amount.minorUnits)
                .addValue("createdAt", Timestamp.from(hold.createdAt))
                .addValue("status", hold.status.name)
            jdbc.update(UPSERT_HOLD, params)
        }
    }

    private fun accountParams(account: Account) = MapSqlParameterSource()
        .addValue("id", account.id)
        .addValue("ownerUserId", account.ownerUserId)
        .addValue("accountNumber", account.accountNumber)
        .addValue("type", account.type.name)
        .addValue("status", account.status.name)
        .addValue("currency", account.currency.currencyCode)
        .addValue("availableMinor", account.availableBalance.minorUnits)
        .addValue("heldMinor", account.heldBalance.minorUnits)
        .addValue("version", account.version)

    private fun loadAll(sql: String, params: MapSqlParameterSource): List<Account> {
        val rows = jdbc.query(sql, params) { rs, _ -> mapRow(rs) }
        if (rows.isEmpty()) return emptyList()
        val holds = loadHolds(rows.map { it.id })
        return rows.map { it.toAccount(holds[it.id].orEmpty()) }
    }

    private fun loadHolds(accountIds: List<UUID>): Map<UUID, List<Hold>> {
        val params = MapSqlParameterSource("accountIds", accountIds)
        return jdbc.query(SELECT_HOLDS, params) { rs, _ ->
            rs.getObject("account_id", UUID::class.java) to Hold(
                id = rs.getObject("id", UUID::class.java),
                amount = Money.ofMinor(rs.getLong("amount_minor"), LKR),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                status = HoldStatus.valueOf(rs.getString("status")),
            )
        }.groupBy({ it.first }, { it.second })
    }

    private fun mapRow(rs: ResultSet): AccountRow {
        val currency = Currency.getInstance(rs.getString("currency").trim())
        return AccountRow(
            id = rs.getObject("id", UUID::class.java),
            ownerUserId = rs.getObject("owner_user_id", UUID::class.java),
            accountNumber = rs.getString("account_number"),
            type = AccountType.valueOf(rs.getString("type")),
            status = AccountStatus.valueOf(rs.getString("status")),
            currency = currency,
            available = Money.ofMinor(rs.getLong("available_minor"), currency),
            held = Money.ofMinor(rs.getLong("held_minor"), currency),
            version = rs.getLong("version"),
        )
    }

    /** Flat projection of the `account` row, kept separate so holds can be joined in afterwards. */
    private data class AccountRow(
        val id: UUID,
        val ownerUserId: UUID,
        val accountNumber: String,
        val type: AccountType,
        val status: AccountStatus,
        val currency: Currency,
        val available: Money,
        val held: Money,
        val version: Long,
    ) {
        fun toAccount(holds: List<Hold>) = Account(
            id = id,
            ownerUserId = ownerUserId,
            accountNumber = accountNumber,
            type = type,
            status = status,
            currency = currency,
            availableBalance = available,
            heldBalance = held,
            version = version,
            holds = holds,
        )
    }

    private companion object {
        val LKR: Currency = Money.LKR

        const val COLUMNS = """
            id, owner_user_id, account_number, type, status, currency,
            available_minor, held_minor, version
        """

        const val SELECT_BY_ID = "SELECT $COLUMNS FROM account WHERE id = :id"
        const val SELECT_BY_OWNER =
            "SELECT $COLUMNS FROM account WHERE owner_user_id = :ownerUserId ORDER BY account_number"
        const val SELECT_BY_NUMBER = "SELECT $COLUMNS FROM account WHERE account_number = :accountNumber"

        const val SELECT_HOLDS = """
            SELECT id, account_id, amount_minor, created_at, status
            FROM account_hold
            WHERE account_id IN (:accountIds)
            ORDER BY created_at
        """

        const val INSERT_ACCOUNT = """
            INSERT INTO account (
                id, owner_user_id, account_number, type, status, currency,
                available_minor, held_minor, version, created_at, updated_at
            ) VALUES (
                :id, :ownerUserId, :accountNumber, :type, :status, :currency,
                :availableMinor, :heldMinor, :version, now(), now()
            )
            ON CONFLICT (id) DO NOTHING
        """

        const val UPDATE_ACCOUNT = """
            UPDATE account SET
                owner_user_id   = :ownerUserId,
                account_number  = :accountNumber,
                type            = :type,
                status          = :status,
                currency        = :currency,
                available_minor = :availableMinor,
                held_minor      = :heldMinor,
                version         = :nextVersion,
                updated_at      = now()
            WHERE id = :id AND version = :version
        """

        const val UPSERT_HOLD = """
            INSERT INTO account_hold (id, account_id, amount_minor, created_at, status)
            VALUES (:id, :accountId, :amountMinor, :createdAt, :status)
            ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status
        """

        fun Account.withVersion(version: Long) = Account(
            id = id,
            ownerUserId = ownerUserId,
            accountNumber = accountNumber,
            type = type,
            status = status,
            currency = currency,
            availableBalance = availableBalance,
            heldBalance = heldBalance,
            version = version,
            holds = holds,
        )
    }
}
