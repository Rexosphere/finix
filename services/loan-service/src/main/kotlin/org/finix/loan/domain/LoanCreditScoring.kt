package org.finix.loan.domain

import org.finix.kernel.domain.Money

/**
 * Deterministic SME credit score from principal size and an optional risk hint.
 *
 * Kept pure so demos and unit tests never depend on a model server. Larger principals
 * score lower; HIGH risk hints subtract, LOW hints add.
 */
object LoanCreditScoring {

    const val APPROVE_THRESHOLD: Int = 60
    const val MAX_SCORE: Int = 100
    const val MIN_SCORE: Int = 0

    private const val FIFTY_THOUSAND_MINOR: Long = 5_000_000L
    private const val ONE_FIFTY_THOUSAND_MINOR: Long = 15_000_000L
    private const val FIVE_HUNDRED_THOUSAND_MINOR: Long = 50_000_000L

    private const val SCORE_SMALL: Int = 80
    private const val SCORE_MEDIUM: Int = 65
    private const val SCORE_LARGE: Int = 50
    private const val SCORE_XL: Int = 30

    private const val HINT_LOW_BONUS: Int = 10
    private const val HINT_MEDIUM_PENALTY: Int = 10
    private const val HINT_HIGH_PENALTY: Int = 25

    fun score(principal: Money, riskHint: String?): Int {
        var base = when {
            principal.minorUnits <= FIFTY_THOUSAND_MINOR -> SCORE_SMALL
            principal.minorUnits <= ONE_FIFTY_THOUSAND_MINOR -> SCORE_MEDIUM
            principal.minorUnits <= FIVE_HUNDRED_THOUSAND_MINOR -> SCORE_LARGE
            else -> SCORE_XL
        }
        when (riskHint?.trim()?.uppercase()) {
            "LOW" -> base += HINT_LOW_BONUS
            "MEDIUM" -> base -= HINT_MEDIUM_PENALTY
            "HIGH" -> base -= HINT_HIGH_PENALTY
        }
        return base.coerceIn(MIN_SCORE, MAX_SCORE)
    }

    fun isApproved(score: Int): Boolean = score >= APPROVE_THRESHOLD
}
