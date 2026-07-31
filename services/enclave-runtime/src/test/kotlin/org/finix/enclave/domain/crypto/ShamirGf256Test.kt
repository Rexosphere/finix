package org.finix.enclave.domain.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

class Gf256Test : StringSpec({

    "add is xor and is its own inverse" {
        checkAll(Arb.int(0..255), Arb.int(0..255)) { a, b ->
            Gf256.add(a, b) shouldBe ((a xor b) and 0xFF)
            Gf256.add(Gf256.add(a, b), b) shouldBe (a and 0xFF)
            Gf256.sub(a, b) shouldBe Gf256.add(a, b)
        }
    }

    "mul by one is identity; mul by zero is zero" {
        checkAll(Arb.int(0..255)) { a ->
            Gf256.mul(a, 1) shouldBe (a and 0xFF)
            Gf256.mul(a, 0) shouldBe 0
        }
    }

    "mul is commutative on samples" {
        checkAll(Arb.int(0..255), Arb.int(0..255)) { a, b ->
            Gf256.mul(a, b) shouldBe Gf256.mul(b, a)
        }
    }

    "div undoes mul for non-zero divisor" {
        checkAll(Arb.int(0..255), Arb.int(1..255)) { a, b ->
            Gf256.div(Gf256.mul(a, b), b) shouldBe (a and 0xFF)
        }
        Gf256.div(0, 7) shouldBe 0
        shouldThrow<IllegalArgumentException> { Gf256.div(1, 0) }
    }

    "pow and error paths" {
        Gf256.pow(0x03, 0) shouldBe 1
        var expected = 1
        repeat(4) { expected = Gf256.mul(expected, 0x02) }
        Gf256.pow(0x02, 4) shouldBe expected
        shouldThrow<IllegalArgumentException> { Gf256.pow(2, -1) }
    }
})

/**
 * Test-only splitter matching vault's GF(256) Shamir so reconstruct fixtures stay local.
 */
internal object ShamirSplit {
    fun split(secret: ByteArray, n: Int = 5, k: Int = 3): List<Shamir.Share> {
        require(k in 2..n) { "need 2 <= k <= n" }
        require(n < 256) { "n must fit in GF(256)" }
        val random = java.security.SecureRandom()
        val sharesY = Array(n) { ByteArray(secret.size) }
        for (byteIndex in secret.indices) {
            // One polynomial per byte; evaluate at every share x (same poly for all custodians).
            val coeffs = IntArray(k) { i ->
                if (i == 0) secret[byteIndex].toInt() and 0xFF else random.nextInt(256)
            }
            for (shareIndex in 0 until n) {
                val x = shareIndex + 1
                var acc = 0
                var powX = 1
                for (c in coeffs) {
                    acc = Gf256.add(acc, Gf256.mul(c, powX))
                    powX = Gf256.mul(powX, x)
                }
                sharesY[shareIndex][byteIndex] = acc.toByte()
            }
        }
        return List(n) { i -> Shamir.Share(x = i + 1, y = sharesY[i]) }
    }
}

class ShamirReconstructTest : StringSpec({

    "any 3 of 5 shares reconstruct the secret" {
        val secret = ByteArray(32) { (it * 7 + 3).toByte() }
        val shares = ShamirSplit.split(secret, n = 5, k = 3)
        val combos = shares.indices.toList().combinations(3)
        combos.forEach { idxs ->
            val subset = idxs.map { shares[it] }
            val recovered = Shamir.reconstruct(subset)
            recovered.contentEquals(secret) shouldBe true
            SecureBytes.wipe(recovered)
        }
    }

    "two shares do not equal the secret" {
        val secret = ByteArray(32) { 0xA5.toByte() }
        val shares = ShamirSplit.split(secret, n = 5, k = 3)
        val recovered = Shamir.reconstruct(shares.take(2))
        recovered.contentEquals(secret) shouldBe false
        SecureBytes.wipe(recovered)
    }

    "Share validation and reconstruct guards" {
        shouldThrow<IllegalArgumentException> { Shamir.Share(0, byteArrayOf(1)) }
        shouldThrow<IllegalArgumentException> { Shamir.Share(1, byteArrayOf()) }
        val a = Shamir.Share(1, byteArrayOf(1, 2))
        val b = Shamir.Share(1, byteArrayOf(1, 2))
        val c = Shamir.Share(2, byteArrayOf(1, 2))
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        a shouldNotBe c
        shouldThrow<IllegalArgumentException> { Shamir.reconstruct(listOf(a)) }
        shouldThrow<IllegalArgumentException> {
            Shamir.reconstruct(listOf(a, Shamir.Share(1, byteArrayOf(3, 4))))
        }
        shouldThrow<IllegalArgumentException> {
            Shamir.reconstruct(listOf(a, Shamir.Share(2, byteArrayOf(1))))
        }
    }
})

class SecureBytesTest : StringSpec({
    "wipe zeros the buffer and increments the counter" {
        SecureBytes.resetWipeCounter()
        val buf = byteArrayOf(1, 2, 3)
        SecureBytes.wipe(buf)
        buf.toList() shouldBe listOf(0.toByte(), 0, 0)
        SecureBytes.wipeInvocations shouldBe 1
        SecureBytes.wipe(null)
        SecureBytes.wipeInvocations shouldBe 1
    }
})

private fun <T> List<T>.combinations(k: Int): List<List<T>> {
    if (k == 0) return listOf(emptyList())
    if (k > size) return emptyList()
    val result = mutableListOf<List<T>>()
    fun rec(start: Int, acc: List<T>) {
        if (acc.size == k) {
            result += acc
            return
        }
        for (i in start until size) {
            rec(i + 1, acc + this[i])
        }
    }
    rec(0, emptyList())
    return result
}
