package org.finix.enclave.domain.crypto

/**
 * Reconstruct-only Shamir secret sharing over [Gf256], mirroring vault's Lagrange path.
 *
 * Shares are `(x, y)` where `x` is a non-zero field element (custodian index 1..n) and `y` is
 * a byte array of the same length as the secret. Reconstruction is independent per byte.
 *
 * Split lives in vault-service; the enclave only interpolates. Tests ship a tiny local splitter
 * under `src/test` so known 3-of-5 fixtures do not pull vault onto the classpath.
 */
object Shamir {

    data class Share(val x: Int, val y: ByteArray) {
        init {
            require(x in 1..255) { "share x must be in 1..255, got $x" }
            require(y.isNotEmpty()) { "share y must be non-empty" }
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is Share && x == other.x && y.contentEquals(other.y))

        override fun hashCode(): Int = 31 * x + y.contentHashCode()
    }

    /**
     * Lagrange interpolation at x=0 for each byte position.
     *
     * Callers must supply at least the threshold number of distinct-x shares; this API does
     * not know `k` — wrong cardinality yields a wrong secret rather than a loud failure.
     * The returned buffer is owned by the caller and must be [SecureBytes.wipe]d after use.
     */
    fun reconstruct(shares: List<Share>): ByteArray {
        require(shares.size >= 2) { "need at least 2 shares to reconstruct" }
        val xs = shares.map { it.x }
        require(xs.toSet().size == xs.size) { "duplicate share x values" }
        val len = shares.first().y.size
        require(shares.all { it.y.size == len }) { "share lengths must match" }

        val secret = ByteArray(len)
        for (byteIndex in 0 until len) {
            var acc = 0
            for (i in shares.indices) {
                val xi = shares[i].x
                val yi = shares[i].y[byteIndex].toInt() and 0xFF
                var basis = 1
                for (j in shares.indices) {
                    if (i == j) continue
                    val xj = shares[j].x
                    // ℓ_i(0) = Π_{j≠i} (0 − x_j) / (x_i − x_j); in char 2, − = +.
                    val num = xj and 0xff
                    val den = Gf256.add(xi, xj)
                    basis = Gf256.mul(basis, Gf256.div(num, den))
                }
                acc = Gf256.add(acc, Gf256.mul(yi, basis))
            }
            secret[byteIndex] = acc.toByte()
        }
        return secret
    }
}
