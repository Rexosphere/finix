package org.finix.loan.adapter.out.persistence

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.Money
import org.finix.loan.application.port.LoanRepository
import org.finix.loan.domain.Loan
import org.finix.loan.domain.LoanStatus
import org.finix.loan.domain.RepaymentScheduleItem
import org.finix.loan.domain.RepaymentStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * JDBC adapter over `loan` + `loan_repayment` (V1__loan.sql).
 *
 * The repayment schedule is written with the loan rather than through its own port because a
 * decision that approved a loan without its installments would be an incomplete aggregate.
 */
@Repository
class JdbcLoanRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : LoanRepository {

    override fun save(loan: Loan): Loan {
        val updated = jdbc.update(UPDATE_LOAN, loanParams(loan).addValue("nextVersion", loan.version + 1))
        val persistedVersion = if (updated > 0) {
            loan.version + 1
        } else {
            insertOrFail(loan)
            loan.version
        }
        saveSchedule(loan)
        return loan.withVersion(persistedVersion)
    }

    override fun findById(id: UUID): Loan? =
        loadAll(SELECT_BY_ID, MapSqlParameterSource("id", id)).firstOrNull()

    override fun findByBorrower(borrowerUserId: UUID): List<Loan> =
        loadAll(SELECT_BY_BORROWER, MapSqlParameterSource("borrowerUserId", borrowerUserId))

    override fun findAll(): List<Loan> = loadAll(SELECT_ALL, MapSqlParameterSource())

    private fun insertOrFail(loan: Loan) {
        val inserted = jdbc.update(INSERT_LOAN, loanParams(loan))
        if (inserted == 0) {
            DomainError.ConcurrentModification("Loan", loan.id.toString()).raise()
        }
    }

    private fun saveSchedule(loan: Loan) {
        loan.schedule.forEach { item ->
            val params = MapSqlParameterSource()
                .addValue("id", item.id)
                .addValue("loanId", loan.id)
                .addValue("installmentNumber", item.installmentNumber)
                .addValue("dueDate", java.sql.Date.valueOf(item.dueDate))
                .addValue("amountMinor", item.amount.minorUnits)
                .addValue("currency", item.amount.currency.currencyCode)
                .addValue("status", item.status.name)
            jdbc.update(UPSERT_REPAYMENT, params)
        }
    }

    private fun loanParams(loan: Loan) = MapSqlParameterSource()
        .addValue("id", loan.id)
        .addValue("borrowerUserId", loan.borrowerUserId)
        .addValue("accountId", loan.accountId)
        .addValue("principalMinor", loan.principal.minorUnits)
        .addValue("currency", loan.principal.currency.currencyCode)
        .addValue("status", loan.status.name)
        .addValue("termMonths", loan.termMonths)
        .addValue("creditScore", loan.creditScore)
        .addValue("riskHint", loan.riskHint)
        .addValue("appliedAt", Timestamp.from(loan.appliedAt))
        .addValue("decidedAt", loan.decidedAt?.let { Timestamp.from(it) })
        .addValue("version", loan.version)

    private fun loadAll(sql: String, params: MapSqlParameterSource): List<Loan> {
        val rows = jdbc.query(sql, params) { rs, _ -> mapRow(rs) }
        if (rows.isEmpty()) return emptyList()
        val schedules = loadSchedules(rows.map { it.id })
        return rows.map { it.toLoan(schedules[it.id].orEmpty()) }
    }

    private fun loadSchedules(loanIds: List<UUID>): Map<UUID, List<RepaymentScheduleItem>> =
        jdbc.query(SELECT_REPAYMENTS, MapSqlParameterSource("loanIds", loanIds)) { rs, _ ->
            rs.getObject("loan_id", UUID::class.java) to RepaymentScheduleItem(
                id = rs.getObject("id", UUID::class.java),
                installmentNumber = rs.getInt("installment_number"),
                dueDate = rs.getDate("due_date").toLocalDate(),
                amount = Money.ofMinor(
                    rs.getLong("amount_minor"),
                    Currency.getInstance(rs.getString("currency").trim()),
                ),
                status = RepaymentStatus.valueOf(rs.getString("status")),
            )
        }.groupBy({ it.first }, { it.second })

    private fun mapRow(rs: ResultSet): LoanRow {
        val currency = Currency.getInstance(rs.getString("currency").trim())
        return LoanRow(
            id = rs.getObject("id", UUID::class.java),
            borrowerUserId = rs.getObject("borrower_user_id", UUID::class.java),
            accountId = rs.getObject("account_id", UUID::class.java),
            principal = Money.ofMinor(rs.getLong("principal_minor"), currency),
            termMonths = rs.getInt("term_months"),
            status = LoanStatus.valueOf(rs.getString("status")),
            creditScore = rs.getInt("credit_score").takeUnless { rs.wasNull() },
            riskHint = rs.getString("risk_hint"),
            appliedAt = rs.getTimestamp("applied_at").toInstant(),
            decidedAt = rs.getTimestamp("decided_at")?.toInstant(),
            version = rs.getLong("version"),
        )
    }

    /** `loan` row before its repayment schedule is attached. */
    @Suppress("LongParameterList")
    private data class LoanRow(
        val id: UUID,
        val borrowerUserId: UUID,
        val accountId: UUID,
        val principal: Money,
        val termMonths: Int,
        val status: LoanStatus,
        val creditScore: Int?,
        val riskHint: String?,
        val appliedAt: Instant,
        val decidedAt: Instant?,
        val version: Long,
    ) {
        fun toLoan(schedule: List<RepaymentScheduleItem>) = Loan(
            id = id,
            borrowerUserId = borrowerUserId,
            accountId = accountId,
            principal = principal,
            termMonths = termMonths,
            status = status,
            schedule = schedule,
            creditScore = creditScore,
            riskHint = riskHint,
            appliedAt = appliedAt,
            decidedAt = decidedAt,
            version = version,
        )
    }

    private companion object {
        const val COLUMNS = """
            id, borrower_user_id, account_id, principal_minor, currency, status, term_months,
            credit_score, risk_hint, applied_at, decided_at, version
        """

        const val SELECT_BY_ID = "SELECT $COLUMNS FROM loan WHERE id = :id"
        const val SELECT_BY_BORROWER =
            "SELECT $COLUMNS FROM loan WHERE borrower_user_id = :borrowerUserId ORDER BY applied_at DESC"
        const val SELECT_ALL = "SELECT $COLUMNS FROM loan ORDER BY applied_at DESC"

        const val SELECT_REPAYMENTS = """
            SELECT id, loan_id, installment_number, due_date, amount_minor, currency, status
            FROM loan_repayment
            WHERE loan_id IN (:loanIds)
            ORDER BY installment_number
        """

        const val INSERT_LOAN = """
            INSERT INTO loan (
                id, borrower_user_id, account_id, principal_minor, currency, status, term_months,
                credit_score, risk_hint, applied_at, decided_at, version, created_at, updated_at
            ) VALUES (
                :id, :borrowerUserId, :accountId, :principalMinor, :currency, :status, :termMonths,
                :creditScore, :riskHint, :appliedAt, :decidedAt, :version, now(), now()
            )
            ON CONFLICT (id) DO NOTHING
        """

        const val UPDATE_LOAN = """
            UPDATE loan SET
                status       = :status,
                credit_score = :creditScore,
                risk_hint    = :riskHint,
                decided_at   = :decidedAt,
                version      = :nextVersion,
                updated_at   = now()
            WHERE id = :id AND version = :version
        """

        const val UPSERT_REPAYMENT = """
            INSERT INTO loan_repayment (
                id, loan_id, installment_number, due_date, amount_minor, currency, status
            ) VALUES (
                :id, :loanId, :installmentNumber, :dueDate, :amountMinor, :currency, :status
            )
            ON CONFLICT (loan_id, installment_number) DO UPDATE SET
                due_date     = EXCLUDED.due_date,
                amount_minor = EXCLUDED.amount_minor,
                status       = EXCLUDED.status
        """

        fun Loan.withVersion(version: Long) = Loan(
            id = id,
            borrowerUserId = borrowerUserId,
            accountId = accountId,
            principal = principal,
            termMonths = termMonths,
            status = status,
            schedule = schedule,
            creditScore = creditScore,
            riskHint = riskHint,
            appliedAt = appliedAt,
            decidedAt = decidedAt,
            version = version,
        )
    }
}
