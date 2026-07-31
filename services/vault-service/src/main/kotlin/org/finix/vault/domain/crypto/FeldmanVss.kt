package org.finix.vault.domain.crypto

import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Feldman verifiable secret sharing over the **secp256r1** prime-order group (via BouncyCastle).
 *
 * Shamir reconstruction of the Master Key still happens over [Gf256] ([Shamir]); Feldman runs in
 * parallel on a scalar derived from the same secret so a custodian (or attacker) who forges a
 * share ordinate is rejected **before** Lagrange runs. Commitments `C_j = a_j · G` are public;
 * verification is the additive-group form of `y·G = Σ C_j · x^j`.
 *
 * ArchUnit permits BouncyCastle in the domain (it only bans Spring / JPA / Jackson / servlet /
 * Kafka). The curve parameters are the NIST P-256 / secp256r1 set — documented, widely reviewed,
 * and already on the FINIX dependency path for ML-KEM.
 */
object FeldmanVss {

    private val CURVE: X9ECParameters = CustomNamedCurves.getByName("secp256r1")
    private val N: BigInteger = CURVE.n
    private val G: ECPoint = CURVE.g
    private val defaultRandom = SecureRandom()

    /** Encoded length of a scalar share ordinate (curve order fits in 32 bytes). */
    const val SCALAR_BYTES: Int = 32

    /**
     * Split [secret] into [n] Feldman shares with threshold [k], returning the shares and the
     * public coefficient commitments (compressed EC points).
     *
     * The Feldman secret coefficient is `a₀ = SHA-256(secret) mod n` so a 32-byte Master Key
     * always lands in the scalar field without bias toward short BigIntegers.
     */
    fun split(
        secret: ByteArray,
        n: Int = 5,
        k: Int = 3,
        random: SecureRandom = defaultRandom,
    ): Pair<List<Share>, List<ByteArray>> {
        require(secret.isNotEmpty()) { "secret must be non-empty" }
        require(k >= 2) { "threshold k must be >= 2" }
        require(n >= k) { "n must be >= k" }

        val coeffs = ArrayList<BigInteger>(k)
        try {
            coeffs.add(scalarFromSecret(secret))
            repeat(k - 1) {
                coeffs.add(randomScalar(random))
            }
            val commitments = coeffs.map { coeff -> encodePoint(G.multiply(coeff).normalize()) }
            val shares = (1..n).map { x ->
                val y = evaluate(coeffs, BigInteger.valueOf(x.toLong()))
                Share(x = x, y = toFixed(y))
            }
            return shares to commitments
        } finally {
            // Best-effort wipe of mutable coefficient list references (BigInteger is immutable;
            // clearing the list drops the only local handles we created).
            coeffs.clear()
        }
    }

    /**
     * Returns `true` iff [share] is consistent with the public [commitments] produced by [split]
     * for the same secret and threshold.
     *
     * A forged ordinate fails this check with overwhelming probability.
     */
    fun verifyShare(share: Share, commitments: List<ByteArray>): Boolean {
        if (commitments.isEmpty()) return false
        if (share.y.size != SCALAR_BYTES) return false
        return runCatching {
            val y = BigInteger(1, share.y).mod(N)
            val lhs = G.multiply(y).normalize()
            var rhs = CURVE.curve.infinity
            var xPow = BigInteger.ONE
            val x = BigInteger.valueOf(share.x.toLong())
            for (encoded in commitments) {
                val point = decodePoint(encoded)
                rhs = rhs.add(point.multiply(xPow)).normalize()
                xPow = xPow.multiply(x).mod(N)
            }
            lhs.equals(rhs)
        }.getOrDefault(false)
    }

    /** Derive the Feldman `a₀` scalar from raw key bytes (never interpret the key as a String). */
    fun scalarFromSecret(secret: ByteArray): BigInteger {
        val digest = org.finix.kernel.crypto.Hashing.sha256(secret)
        return try {
            BigInteger(1, digest).mod(N)
        } finally {
            digest.fill(0)
        }
    }

    private fun evaluate(coeffs: List<BigInteger>, x: BigInteger): BigInteger {
        var result = BigInteger.ZERO
        for (i in coeffs.indices.reversed()) {
            result = result.multiply(x).add(coeffs[i]).mod(N)
        }
        return result
    }

    private fun randomScalar(random: SecureRandom): BigInteger {
        var candidate: BigInteger
        do {
            candidate = BigInteger(N.bitLength(), random).mod(N)
        } while (candidate == BigInteger.ZERO)
        return candidate
    }

    private fun toFixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(SCALAR_BYTES)
        when {
            raw.size == SCALAR_BYTES -> return raw
            raw.size == SCALAR_BYTES + 1 && raw[0] == 0.toByte() -> {
                System.arraycopy(raw, 1, out, 0, SCALAR_BYTES)
            }
            raw.size < SCALAR_BYTES -> {
                System.arraycopy(raw, 0, out, SCALAR_BYTES - raw.size, raw.size)
            }
            else -> {
                // Should not happen for scalars < n; truncate from the left just in case.
                System.arraycopy(raw, raw.size - SCALAR_BYTES, out, 0, SCALAR_BYTES)
            }
        }
        return out
    }

    private fun encodePoint(point: ECPoint): ByteArray = point.getEncoded(true)

    private fun decodePoint(encoded: ByteArray): ECPoint = CURVE.curve.decodePoint(encoded)
}
