package org.finix.orchestrator.adapter.out.persistence

import org.finix.kernel.domain.Money
import org.finix.orchestrator.application.port.SagaRepository
import org.finix.orchestrator.domain.SagaState
import org.finix.orchestrator.domain.TransferSaga
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.Currency
import java.util.UUID

/** JDBC adapter over `transfer_saga` (V1 + V2__risk_fields + V3__hold_committed + V4__credit_outcome_unknown). */
@Repository
class JdbcSagaRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : SagaRepository {

    /**
     * Upsert keyed on the saga id. The saga advances through many states within one transfer and
     * every step calls [save]; an insert-only adapter would need the caller to know which step
     * is the first, which is exactly the coupling the port exists to avoid.
     */
    override fun save(saga: TransferSaga): TransferSaga {
        val params = MapSqlParameterSource()
            .addValue("id", saga.id)
            .addValue("fromAccountId", saga.fromAccountId)
            .addValue("toAccountId", saga.toAccountId)
            .addValue("amountMinor", saga.amount.minorUnits)
            .addValue("currency", saga.amount.currency.currencyCode)
            .addValue("state", saga.state.name)
            .addValue("holdId", saga.holdId)
            .addValue("ledgerPosted", saga.ledgerPosted)
            .addValue("holdCommitted", saga.holdCommitted)
            .addValue("creditOutcomeUnknown", saga.creditOutcomeUnknown)
            .addValue("creditRefused", saga.creditRefused)
            .addValue("refundAttempted", saga.refundAttempted)
            .addValue("failureReason", saga.failureReason)
            .addValue("riskScore", saga.riskScore)
            .addValue("riskDecision", saga.riskDecision)
            .addValue("createdAt", Timestamp.from(saga.createdAt))
            .addValue("updatedAt", Timestamp.from(saga.updatedAt))
        jdbc.update(UPSERT, params)
        return saga
    }

    override fun findById(id: UUID): TransferSaga? =
        jdbc.query(SELECT_BY_ID, MapSqlParameterSource("id", id)) { rs, _ -> mapRow(rs) }.firstOrNull()

    private fun mapRow(rs: ResultSet) = TransferSaga(
        id = rs.getObject("id", UUID::class.java),
        fromAccountId = rs.getObject("from_account_id", UUID::class.java),
        toAccountId = rs.getObject("to_account_id", UUID::class.java),
        amount = Money.ofMinor(
            rs.getLong("amount_minor"),
            Currency.getInstance(rs.getString("currency").trim()),
        ),
        state = SagaState.valueOf(rs.getString("state")),
        holdId = rs.getObject("hold_id", UUID::class.java),
        ledgerPosted = rs.getBoolean("ledger_posted"),
        holdCommitted = rs.getBoolean("hold_committed"),
        creditOutcomeUnknown = rs.getBoolean("credit_outcome_unknown"),
        creditRefused = rs.getBoolean("credit_refused"),
        refundAttempted = rs.getBoolean("refund_attempted"),
        failureReason = rs.getString("failure_reason"),
        riskScore = rs.getInt("risk_score").takeUnless { rs.wasNull() },
        riskDecision = rs.getString("risk_decision"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    internal companion object {
        const val COLUMNS = """
            id, from_account_id, to_account_id, amount_minor, currency, state, hold_id,
            ledger_posted, hold_committed, credit_outcome_unknown, credit_refused,
            refund_attempted, failure_reason, risk_score, risk_decision, created_at, updated_at
        """

        const val SELECT_BY_ID = "SELECT $COLUMNS FROM transfer_saga WHERE id = :id"

        /**
         * Safety facts are merged with `OR`, never overwritten.
         *
         * `transfer_saga` has no optimistic lock, so this upsert is last-writer-wins. That is
         * harmless for `state` or `failure_reason`, but a fact whose TRUE value means "do not
         * financially reverse this transfer" must not be downgraded by a writer holding a stale
         * copy: a concurrent admin compensation that read the row a moment before it was frozen
         * would otherwise erase the freeze and go on to refund the sender. Making the merge
         * monotonic puts that guarantee in the one place that sees every writer.
         */
        const val UPSERT = """
            INSERT INTO transfer_saga (
                id, from_account_id, to_account_id, amount_minor, currency, state, hold_id,
                ledger_posted, hold_committed, credit_outcome_unknown, credit_refused,
                refund_attempted, failure_reason, risk_score, risk_decision, created_at, updated_at
            ) VALUES (
                :id, :fromAccountId, :toAccountId, :amountMinor, :currency, :state, :holdId,
                :ledgerPosted, :holdCommitted, :creditOutcomeUnknown, :creditRefused,
                :refundAttempted, :failureReason, :riskScore, :riskDecision, :createdAt, :updatedAt
            )
            ON CONFLICT (id) DO UPDATE SET
                state          = EXCLUDED.state,
                ledger_posted  = transfer_saga.ledger_posted OR EXCLUDED.ledger_posted,
                hold_committed = transfer_saga.hold_committed OR EXCLUDED.hold_committed,
                credit_outcome_unknown =
                    transfer_saga.credit_outcome_unknown OR EXCLUDED.credit_outcome_unknown,
                credit_refused = transfer_saga.credit_refused OR EXCLUDED.credit_refused,
                refund_attempted = transfer_saga.refund_attempted OR EXCLUDED.refund_attempted,
                failure_reason = EXCLUDED.failure_reason,
                risk_score     = EXCLUDED.risk_score,
                risk_decision  = EXCLUDED.risk_decision,
                updated_at     = EXCLUDED.updated_at
        """
    }
}
