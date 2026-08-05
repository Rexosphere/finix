package org.finix.account.adapter.out.persistence

import org.finix.account.application.port.AccountNumberGenerator
import org.finix.account.domain.AccountType
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

/**
 * Continues the `FINIX-<PRD>-00000001` series the demo personas are seeded with, so a freshly
 * opened account looks like the seeded ones instead of a random UUID.
 *
 * The next number is derived from the existing maximum rather than a sequence because the
 * seeded rows were inserted with fixed numbers; a sequence would start at 1 and immediately
 * collide. `uq_account_number` remains the actual guarantee of uniqueness.
 */
@Component
class JdbcAccountNumberGenerator(
    private val jdbc: NamedParameterJdbcTemplate,
) : AccountNumberGenerator {

    override fun next(type: AccountType): String {
        val nextSerial = jdbc.queryForObject(NEXT_SERIAL, MapSqlParameterSource(), Long::class.java) ?: 1L
        return "$PREFIX-${prefixFor(type)}-%0${SERIAL_WIDTH}d".format(nextSerial)
    }

    private fun prefixFor(type: AccountType): String = when (type) {
        AccountType.SAVINGS -> "SAV"
        AccountType.CURRENT -> "CUR"
        AccountType.WALLET -> "WAL"
    }

    private companion object {
        const val PREFIX = "FINIX"
        const val SERIAL_WIDTH = 8

        const val NEXT_SERIAL = """
            SELECT COALESCE(MAX(CAST(SUBSTRING(account_number FROM '[0-9]+$') AS BIGINT)), 0) + 1
            FROM account
        """
    }
}
