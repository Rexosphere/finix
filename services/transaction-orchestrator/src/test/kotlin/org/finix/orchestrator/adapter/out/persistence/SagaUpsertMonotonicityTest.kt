package org.finix.orchestrator.adapter.out.persistence

import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * D-3 at the SQL boundary.
 *
 * `transfer_saga` has no optimistic lock, so the upsert is last-writer-wins for ordinary fields.
 * That is harmless for `state` or `failure_reason`, but a safety fact whose TRUE value means
 * "do not financially reverse this transfer" must never be downgraded by a writer holding a
 * stale copy of the row — a concurrent admin compensation that read the saga a moment before it
 * was frozen would otherwise erase the freeze and then refund the sender.
 *
 * Merging these columns with `OR` makes the write monotonic in the database itself, which is the
 * only place that sees every writer. This test fails if anyone reverts a fact to plain
 * `= EXCLUDED.x`; the behavioural counterpart lives in `TransferMoneySafetyTest`, whose repository
 * fake rejects the same downgrade on every save.
 */
class SagaUpsertMonotonicityTest {

    @Test
    fun `every safety fact is merged monotonically rather than overwritten`() {
        val sql = JdbcSagaRepository.UPSERT.replace(WHITESPACE, " ")

        SAFETY_FACTS.forEach { column ->
            withClue("$column must survive a write from a stale saga") {
                sql shouldContain "$column = transfer_saga.$column OR EXCLUDED.$column"
            }
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** Columns whose TRUE value forbids a financial reversal, or records that one happened. */
        val SAFETY_FACTS = listOf(
            "ledger_posted",
            "hold_committed",
            "credit_outcome_unknown",
            "credit_refused",
            "refund_attempted",
        )
    }
}
