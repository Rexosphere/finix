package org.finix.vault.domain.crypto

import java.security.SecureRandom

/**
 * One ordinate of a Shamir share. [x] is the public evaluation point (`1..n`); [y] is the
 * secret ordinate evaluated **per byte** of the original secret over [Gf256].
 *
 * Never convert [y] to a [String] — key material stays in [ByteArray] so callers can
 * [ByteArray.fill] it after use.
 */
data class Share(
    val x: Int,
    val y: ByteArray,
) {
    init {
        require(x in 1..255) { "share index x must be in 1..255, got $x" }
        require(y.isNotEmpty()) { "share ordinate must be non-empty" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Share && x == other.x && y.contentEquals(other.y))

    override fun hashCode(): Int = 31 * x + y.contentHashCode()

    override fun toString(): String = "Share(x=$x, y=${y.size} bytes)"
}

/**
 * Shamir secret sharing over [Gf256] (AES field), implemented from scratch.
 *
 * Splits a secret into [n] shares such that any [k] reconstruct it via Lagrange interpolation
 * **per byte**, and fewer than [k] must not deterministically recover the secret (information-
 * theoretic for a uniformly random secret — see property tests).
 *
 * Default parameters `n=5, k=3` match the Master Key custodian ceremony (FR-07).
 */
object Shamir {

    private val defaultRandom = SecureRandom()

    /**
     * Split [secret] into [n] shares with threshold [k].
     *
     * For each byte `s` a random degree-`(k-1)` polynomial `f` with `f(0) = s` is sampled and
     * evaluated at `x = 1..n`. The same `x` is used across all byte lanes so a share is one
     * vector of ordinates.
     */
    fun split(
        secret: ByteArray,
        n: Int = 5,
        k: Int = 3,
        random: SecureRandom = defaultRandom,
    ): List<Share> {
        require(secret.isNotEmpty()) { "secret must be non-empty" }
        require(k >= 2) { "threshold k must be >= 2" }
        require(n >= k) { "n must be >= k" }
        require(n < 256) { "n must be < 256 (GF(256) evaluation points)" }

        val sharesY = Array(n) { ByteArray(secret.size) }
        val coeffs = ByteArray(k)
        try {
            for (byteIndex in secret.indices) {
                coeffs[0] = secret[byteIndex]
                for (i in 1 until k) {
                    coeffs[i] = random.nextInt(256).toByte()
                }
                for (shareIndex in 0 until n) {
                    val x = shareIndex + 1
                    sharesY[shareIndex][byteIndex] = evaluate(coeffs, x).toByte()
                }
            }
            return List(n) { i -> Share(x = i + 1, y = sharesY[i].copyOf()) }
        } finally {
            coeffs.fill(0)
            sharesY.forEach { it.fill(0) }
        }
    }

    /**
     * Reconstruct the secret from at least [k] distinct shares produced by [split].
     *
     * Uses Lagrange interpolation at `x = 0` over [Gf256] for each byte independently.
     */
    fun reconstruct(shares: List<Share>): ByteArray {
        require(shares.isNotEmpty()) { "need at least one share" }
        val length = shares.first().y.size
        require(shares.all { it.y.size == length }) { "shares must have equal length" }
        require(shares.map { it.x }.toSet().size == shares.size) { "share indices must be unique" }

        val secret = ByteArray(length)
        for (byteIndex in 0 until length) {
            var value = 0
            for (i in shares.indices) {
                val xi = shares[i].x
                val yi = shares[i].y[byteIndex].toInt() and 0xff
                var basis = 1
                for (j in shares.indices) {
                    if (i == j) continue
                    val xj = shares[j].x
                    // ℓ_i(0) = Π_{j≠i} (0 − x_j) / (x_i − x_j); in char 2, − = +.
                    val num = xj and 0xff
                    val den = Gf256.add(xi, xj)
                    basis = Gf256.mul(basis, Gf256.div(num, den))
                }
                value = Gf256.add(value, Gf256.mul(yi, basis))
            }
            secret[byteIndex] = value.toByte()
        }
        return secret
    }

    /** Horner evaluation of a degree-`(coeffs.size-1)` polynomial at integer [x] in GF(256). */
    internal fun evaluate(coeffs: ByteArray, x: Int): Int {
        var result = 0
        for (i in coeffs.indices.reversed()) {
            result = Gf256.add(Gf256.mul(result, x), coeffs[i].toInt() and 0xff)
        }
        return result
    }
}
