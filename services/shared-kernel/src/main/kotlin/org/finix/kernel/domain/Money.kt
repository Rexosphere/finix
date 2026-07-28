package org.finix.kernel.domain

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Currency

/**
 * An exact monetary amount.
 *
 * Money is held as **minor units** (cents) in a [Long], never as a floating-point number and
 * never as an unscaled [BigDecimal]. Two consequences that matter for a ledger:
 *
 *  1. Addition and subtraction are exact and overflow-checked.
 *  2. Division cannot silently lose a cent — callers must use [allocate], which distributes
 *     remainder deterministically so that the parts always sum back to the whole.
 *
 * Mixing currencies is a programming error, not a runtime condition to recover from, so it
 * throws [IllegalArgumentException] rather than returning a failure.
 *
 * `@ConsistentCopyVisibility` keeps the generated `copy()` private alongside the constructor, so
 * amounts can only be produced through the validating factories.
 */
@ConsistentCopyVisibility
data class Money private constructor(
    val minorUnits: Long,
    val currency: Currency,
) : Comparable<Money> {

    val amount: BigDecimal
        get() = BigDecimal.valueOf(minorUnits, currency.defaultFractionDigits)

    val isZero: Boolean get() = minorUnits == 0L
    val isPositive: Boolean get() = minorUnits > 0L
    val isNegative: Boolean get() = minorUnits < 0L

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(minorUnits, other.minorUnits), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.subtractExact(minorUnits, other.minorUnits), currency)
    }

    operator fun unaryMinus(): Money = Money(Math.negateExact(minorUnits), currency)

    fun abs(): Money = if (isNegative) -this else this

    /**
     * Multiply by a rate (interest, fee percentage). Rounding is explicit and defaults to
     * HALF_EVEN — banker's rounding — so repeated fee application does not drift upward.
     */
    fun multiply(factor: BigDecimal, rounding: RoundingMode = RoundingMode.HALF_EVEN): Money {
        val scaled = BigDecimal.valueOf(minorUnits)
            .multiply(factor)
            .setScale(0, rounding)
        return Money(scaled.longValueExact(), currency)
    }

    /**
     * Split into [parts] amounts that sum exactly back to this amount. The remainder cents are
     * handed to the leading parts, so `100 cents / 3` yields `34, 33, 33` — never `33, 33, 33`.
     */
    fun allocate(parts: Int): List<Money> {
        require(parts > 0) { "Cannot allocate money across $parts parts" }
        val base = minorUnits / parts
        var remainder = minorUnits % parts
        val step = if (minorUnits < 0) -1L else 1L
        return List(parts) {
            val extra = if (remainder != 0L) step.also { remainder -= step } else 0L
            Money(base + extra, currency)
        }
    }

    /**
     * Split proportionally by [ratios], preserving the total exactly (largest-remainder method).
     */
    fun allocateByRatios(ratios: List<Long>): List<Money> {
        require(ratios.isNotEmpty()) { "Cannot allocate money across an empty ratio list" }
        require(ratios.all { it >= 0 }) { "Allocation ratios must be non-negative" }
        val total = ratios.sumOf { it }
        require(total > 0) { "Allocation ratios must not sum to zero" }

        // Go through BigInteger: `minorUnits * ratio` overflows a Long for large balances.
        val totalBig = BigInteger.valueOf(total)
        val minorBig = BigInteger.valueOf(minorUnits)
        val shares = ratios
            .map { minorBig.multiply(BigInteger.valueOf(it)).divide(totalBig).longValueExact() }
            .toMutableList()
        var remainder = minorUnits - shares.sum()
        val step = if (remainder < 0) -1L else 1L
        var index = 0
        while (remainder != 0L && index < shares.size) {
            shares[index] = shares[index] + step
            remainder -= step
            index++
        }
        return shares.map { Money(it, currency) }
    }

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return minorUnits.compareTo(other.minorUnits)
    }

    /** Canonical wire form: `"LKR 1250.00"` — stable across languages and safe in JSON. */
    @JsonValue
    override fun toString(): String = "${currency.currencyCode} ${amount.toPlainString()}"

    private fun requireSameCurrency(other: Money) =
        require(currency == other.currency) {
            "Currency mismatch: ${currency.currencyCode} vs ${other.currency.currencyCode}"
        }

    companion object {
        val LKR: Currency = Currency.getInstance("LKR")

        fun ofMinor(minorUnits: Long, currency: Currency = LKR) = Money(minorUnits, currency)

        fun of(amount: BigDecimal, currency: Currency = LKR): Money {
            val scaled = amount.setScale(currency.defaultFractionDigits, RoundingMode.UNNECESSARY)
            return Money(scaled.unscaledValue().longValueExact(), currency)
        }

        fun of(amount: String, currency: Currency = LKR): Money = of(BigDecimal(amount), currency)

        fun zero(currency: Currency = LKR) = Money(0, currency)

        /** Parses the canonical [toString] form. */
        @JvmStatic
        @JsonCreator
        fun parse(value: String): Money {
            val parts = value.trim().split(' ', limit = 2)
            require(parts.size == 2) { "Malformed money literal: '$value' (expected '<CCY> <amount>')" }
            return of(BigDecimal(parts[1]), Currency.getInstance(parts[0]))
        }

        fun sum(values: Iterable<Money>, currency: Currency = LKR): Money =
            values.fold(zero(currency)) { acc, m -> acc + m }
    }
}

/** Convenience for tests and seed data: `1_500.lkr()`. */
fun Int.lkr(): Money = Money.of(BigDecimal.valueOf(this.toLong()), Money.LKR)

/** Convenience for tests and seed data: `"1500.75".lkr()`. */
fun String.lkr(): Money = Money.of(this, Money.LKR)
