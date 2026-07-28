package org.finix.kernel.crypto

import java.security.MessageDigest
import java.util.HexFormat

/**
 * SHA-256 primitives shared by the ledger hash chain, the Merkle anchor and the offline
 * voucher protocol. Digests are exchanged as lower-case hex because that form survives
 * JSON, SQL, QR codes and shell scripts without re-encoding.
 */
object Hashing {

    const val ZERO_DIGEST: String = "0000000000000000000000000000000000000000000000000000000000000000"

    private val HEX: HexFormat = HexFormat.of()

    fun sha256(vararg chunks: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        chunks.forEach { digest.update(it) }
        return digest.digest()
    }

    fun sha256Hex(vararg chunks: ByteArray): String = HEX.formatHex(sha256(*chunks))

    fun hex(bytes: ByteArray): String = HEX.formatHex(bytes)

    fun unhex(value: String): ByteArray = HEX.parseHex(value)

    /**
     * One link of the ledger chain: `H(prevHash || canonicalPayload)`.
     *
     * Both operands are hashed as raw bytes — [prevHash] is un-hexed first — so the chain
     * value depends on the *digest*, not on how it happens to be printed.
     */
    fun chain(prevHash: String, canonicalPayload: ByteArray): String =
        sha256Hex(unhex(prevHash), canonicalPayload)

    /** Constant-time comparison, for anywhere a digest is compared against attacker-supplied input. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
