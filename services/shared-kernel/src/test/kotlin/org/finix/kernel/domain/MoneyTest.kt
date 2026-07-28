package org.finix.kernel.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * Money is the one type where a rounding bug becomes a regulator's problem, so the invariants
 * are asserted as properties over generated inputs rather than a handful of examples.
 */
class MoneyTest : StringSpec({

    val usd: Currency = Currency.getInstance("USD")

    "allocation always sums back to the original amount" {
        checkAll(Arb.long(-1_000_000_000L..1_000_000_000L), Arb.int(1..37)) { minor, parts ->
            val money = Money.ofMinor(minor)
            val pieces = money.allocate(parts)
            pieces.size shouldBe parts
            Money.sum(pieces) shouldBe money
        }
    }

    "allocation spreads the remainder rather than dropping it" {
        // 100 cents across 3 ways must be 34/33/33, never 33/33/33 with a cent destroyed.
        Money.ofMinor(100).allocate(3).map { it.minorUnits } shouldBe listOf(34L, 33L, 33L)
    }

    "negative allocation keeps the sign and still sums exactly" {
        Money.ofMinor(-100).allocate(3).map { it.minorUnits } shouldBe listOf(-34L, -33L, -33L)
    }

    "ratio allocation preserves the total for any non-degenerate ratio set" {
        checkAll(Arb.long(0L..1_000_000_000L)) { minor ->
            val money = Money.ofMinor(minor)
            val ratios = listOf(1L, 2L, 3L, 5L)
            Money.sum(money.allocateByRatios(ratios)) shouldBe money
        }
    }

    "ratio allocation does not overflow on very large balances" {
        val huge = Money.ofMinor(Long.MAX_VALUE / 2)
        Money.sum(huge.allocateByRatios(listOf(7L, 11L, 13L))) shouldBe huge
    }

    "addition and subtraction round-trip" {
        checkAll(Arb.long(-1_000_000L..1_000_000L), Arb.long(-1_000_000L..1_000_000L)) { a, b ->
            val x = Money.ofMinor(a)
            val y = Money.ofMinor(b)
            (x + y - y) shouldBe x
        }
    }

    "canonical string form round-trips through parse" {
        checkAll(Arb.long(-1_000_000_000L..1_000_000_000L)) { minor ->
            val money = Money.ofMinor(minor)
            Money.parse(money.toString()) shouldBe money
        }
    }

    "canonical string form is currency-qualified and fixed-scale" {
        Money.of("1250").toString() shouldBe "LKR 1250.00"
        Money.of("0.05").toString() shouldBe "LKR 0.05"
    }

    "mixing currencies is rejected" {
        val lkr = Money.of("10.00")
        val dollars = Money.of("10.00", usd)
        shouldThrow<IllegalArgumentException> { lkr + dollars }
        shouldThrow<IllegalArgumentException> { lkr.compareTo(dollars) }
    }

    "overflow is detected rather than silently wrapping" {
        shouldThrow<ArithmeticException> { Money.ofMinor(Long.MAX_VALUE) + Money.ofMinor(1) }
    }

    "sub-cent input is rejected instead of being rounded away" {
        shouldThrow<ArithmeticException> { Money.of(BigDecimal("10.005")) }
    }

    "interest uses banker's rounding so repeated application does not drift" {
        // 2.5 and 3.5 cents both round to the even neighbour.
        Money.ofMinor(5).multiply(BigDecimal("0.5")).minorUnits shouldBe 2L
        Money.ofMinor(7).multiply(BigDecimal("0.5")).minorUnits shouldBe 4L
        Money.ofMinor(5).multiply(BigDecimal("0.5"), RoundingMode.HALF_UP).minorUnits shouldBe 3L
    }

    "allocating across zero parts is a programming error" {
        shouldThrow<IllegalArgumentException> { Money.ofMinor(100).allocate(0) }
    }

    "zero-sum ratios are rejected" {
        shouldThrow<IllegalArgumentException> { Money.ofMinor(100).allocateByRatios(listOf(0L, 0L)) }
    }

    "non-default currencies are supported end to end" {
        val d = Money.of("12.34", usd)
        d.toString() shouldBe "USD 12.34"
        Money.parse("USD 12.34") shouldBe d
        Money.sum(listOf(d, d), usd) shouldBe Money.of("24.68", usd)
        Money.zero(usd).isZero shouldBe true
        Money.ofMinor(500, usd).amount shouldBe BigDecimal("5.00")
    }

    "an evenly divisible amount allocates without a remainder pass" {
        Money.ofMinor(99).allocate(3).map { it.minorUnits } shouldBe listOf(33L, 33L, 33L)
    }

    "negation and absolute value are exact" {
        (-Money.of("7.50")) shouldBe Money.of("-7.50")
        Money.of("-7.50").abs() shouldBe Money.of("7.50")
        Money.of("7.50").abs() shouldBe Money.of("7.50")
    }

    "a negative total distributes its remainder downward" {
        Money.ofMinor(-100).allocateByRatios(listOf(1L, 1L, 1L))
            .sumOf { it.minorUnits } shouldBe -100L
    }

    "malformed money literals are rejected" {
        shouldThrow<IllegalArgumentException> { Money.parse("1250.00") }
    }

    "comparison follows amount ordering" {
        (Money.of("10.00") > Money.of("9.99")) shouldBe true
        Money.zero().isZero shouldBe true
        Money.of("-1.00").isNegative shouldBe true
    }
})
