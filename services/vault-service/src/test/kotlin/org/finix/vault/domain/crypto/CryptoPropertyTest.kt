package org.finix.vault.domain.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.SecureRandom

class Gf256Test : StringSpec({

    "mul by one is identity" {
        for (a in 0..255) {
            Gf256.mul(a, 1) shouldBe a
            Gf256.mul(1, a) shouldBe a
        }
    }

    "mul by zero is zero" {
        for (a in 0..255) {
            Gf256.mul(a, 0) shouldBe 0
            Gf256.mul(0, a) shouldBe 0
        }
    }

    "mul is commutative on samples" {
        val samples = listOf(0x01, 0x02, 0x53, 0xca, 0xff, 0x11, 0x7a)
        for (a in samples) {
            for (b in samples) {
                Gf256.mul(a, b) shouldBe Gf256.mul(b, a)
            }
        }
    }

    "mul is associative on samples" {
        val samples = listOf(0x02, 0x03, 0x53, 0xca, 0x7f)
        for (a in samples) {
            for (b in samples) {
                for (c in samples) {
                    Gf256.mul(Gf256.mul(a, b), c) shouldBe Gf256.mul(a, Gf256.mul(b, c))
                }
            }
        }
    }

    "div undoes mul" {
        for (a in 1..255) {
            for (b in 1..255 step 17) {
                Gf256.div(Gf256.mul(a, b), b) shouldBe a
            }
        }
    }

    "add is xor" {
        Gf256.add(0x53, 0xca) shouldBe (0x53 xor 0xca)
        Gf256.add(0xff, 0xff) shouldBe 0
    }

    "pow matches repeated mul" {
        var expected = 1
        repeat(5) { expected = Gf256.mul(expected, 0x03) }
        Gf256.pow(0x03, 5) shouldBe expected
        Gf256.pow(0x53, 0) shouldBe 1
    }

    "inv times value is one" {
        for (a in 1..255) {
            Gf256.mul(a, Gf256.inv(a)) shouldBe 1
        }
        shouldThrow<IllegalArgumentException> { Gf256.inv(0) }
        shouldThrow<IllegalArgumentException> { Gf256.div(1, 0) }
        shouldThrow<IllegalArgumentException> { Gf256.pow(2, -1) }
    }
})

class ShamirTest : StringSpec({

    val random = SecureRandom(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

    "all C(5,3)=10 combinations reconstruct the identical secret" {
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val shares = Shamir.split(secret, n = 5, k = 3, random = random)
        val combos = combinations(shares, 3)
        combos.size shouldBe 10
        for (combo in combos) {
            Shamir.reconstruct(combo).contentEquals(secret) shouldBe true
        }
    }

    "two shares do not equal the secret" {
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val shares = Shamir.split(secret, n = 5, k = 3, random = random)
        // Any pair of shares, reconstructed as if threshold were 2, must not match the secret
        // with a proper k=3 polynomial — and the share ordinates themselves are not the secret.
        for (i in shares.indices) {
            for (j in i + 1 until shares.size) {
                shares[i].y.contentEquals(secret) shouldBe false
                shares[j].y.contentEquals(secret) shouldBe false
                // Reconstructing with only 2 shares of a degree-2 polynomial yields garbage ≠ secret.
                val forged = Shamir.reconstruct(listOf(shares[i], shares[j]))
                forged.contentEquals(secret) shouldBe false
            }
        }
    }

    "2-of-3 recovery shares round-trip" {
        val secret = byteArrayOf(0x0a, 0x0b, 0x0c, 0x0d)
        val shares = Shamir.split(secret, n = 3, k = 2, random = random)
        Shamir.reconstruct(listOf(shares[0], shares[2])).contentEquals(secret) shouldBe true
    }
})

class FeldmanVssTest : StringSpec({

    val random = SecureRandom(byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2))

    "honest shares verify against commitments" {
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val (shares, commitments) = FeldmanVss.split(secret, n = 5, k = 3, random = random)
        shares.forEach { share ->
            FeldmanVss.verifyShare(share, commitments) shouldBe true
        }
    }

    "forged share fails Feldman verify" {
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val (shares, commitments) = FeldmanVss.split(secret, n = 5, k = 3, random = random)
        val honest = shares[0]
        FeldmanVss.verifyShare(honest, commitments) shouldBe true
        val forgedY = honest.y.copyOf()
        forgedY[0] = (forgedY[0].toInt() xor 0xff).toByte()
        val forged = Share(honest.x, forgedY)
        FeldmanVss.verifyShare(forged, commitments) shouldBe false
        forged shouldNotBe honest
        FeldmanVss.verifyShare(honest, emptyList()) shouldBe false
        FeldmanVss.verifyShare(Share(1, ByteArray(8)), commitments) shouldBe false
    }
})

private fun <T> combinations(items: List<T>, k: Int): List<List<T>> {
    if (k == 0) return listOf(emptyList())
    if (items.isEmpty()) return emptyList()
    val head = items.first()
    val withHead = combinations(items.drop(1), k - 1).map { listOf(head) + it }
    val withoutHead = combinations(items.drop(1), k)
    return withHead + withoutHead
}
