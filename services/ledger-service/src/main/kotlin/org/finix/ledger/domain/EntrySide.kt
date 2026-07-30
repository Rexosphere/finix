package org.finix.ledger.domain

/**
 * The two sides of a double-entry journal line.
 *
 * Every journal must have equal debit and credit totals in the same currency — that invariant
 * is what makes the ledger self-auditing without a separate "balance" column to corrupt.
 */
enum class EntrySide {
    DEBIT,
    CREDIT,
}
