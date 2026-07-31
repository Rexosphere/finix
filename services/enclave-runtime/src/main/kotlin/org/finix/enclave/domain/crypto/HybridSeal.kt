package org.finix.enclave.domain.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.finix.kernel.crypto.Hashing
import org.finix.kernel.crypto.PostQuantum
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * # HybridSeal wire format — must stay identical to `org.finix.vault.application.HybridSeal`
 *
 * Packed blob (big-endian lengths):
 * ```
 * kemCtLen(2) || kemCt || x25519EphemeralPubLen(2) || x25519EphemeralPub || nonce(12) || ciphertext+tag
 * ```
 *
 * KDF: `aesKey = SHA-256(x25519Shared ‖ mlKemShared)` then AES-256-GCM.
 *
 * ## Share payload *inside* the seal (vault `SplitMasterKeyUseCase.packSharePayload`)
 * ```
 * shamirYLen(2) || shamirY || feldmanY(32)
 * ```
 *
 * ## Network-config under master key (`sealWithRawKey` / `openWithRawKey`)
 * ```
 * nonce(12) || ciphertext+tag
 * ```
 *
 * REST maps each packed share to `{ "x": <shareIndex>, "sealedB64": "<blob>" }` plus
 * `sealedNetworkConfigB64` and optional `commitmentsB64`.
 */
object HybridSeal {

    const val GCM_NONCE_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val X25519 = "X25519"
    private const val PROVIDER = "BC"

    private val random = SecureRandom()

    init {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun generateX25519KeyPair(): KeyPair =
        KeyPairGenerator.getInstance(X25519, PROVIDER).generateKeyPair()

    fun decodeX25519Public(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance(X25519, PROVIDER).generatePublic(X509EncodedKeySpec(encoded))

    fun decodeX25519Private(encoded: ByteArray): PrivateKey =
        KeyFactory.getInstance(X25519, PROVIDER).generatePrivate(PKCS8EncodedKeySpec(encoded))

    fun seal(
        plaintext: ByteArray,
        recipientKemPublicKey: PublicKey,
        recipientX25519PublicKey: PublicKey,
    ): ByteArray {
        val encapsulation = PostQuantum.encapsulate(recipientKemPublicKey)
        val ephemeral = generateX25519KeyPair()
        val x25519Secret = agree(ephemeral.private, recipientX25519PublicKey)
        val kemSecret = encapsulation.sharedSecret.encoded
        val aesKey = ByteArray(32)
        val nonce = ByteArray(GCM_NONCE_LENGTH)
        try {
            val combined = Hashing.sha256(x25519Secret, kemSecret)
            System.arraycopy(combined, 0, aesKey, 0, 32)
            SecureBytes.wipe(combined)
            random.nextBytes(nonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ciphertext = cipher.doFinal(plaintext)
            val kemCt = encapsulation.ciphertext
            val ephPub = ephemeral.public.encoded
            return ByteBuffer.allocate(2 + kemCt.size + 2 + ephPub.size + nonce.size + ciphertext.size)
                .putShort(kemCt.size.toShort())
                .put(kemCt)
                .putShort(ephPub.size.toShort())
                .put(ephPub)
                .put(nonce)
                .put(ciphertext)
                .array()
        } finally {
            SecureBytes.wipeAll(aesKey, x25519Secret, kemSecret)
        }
    }

    fun open(
        sealed: ByteArray,
        recipientKemPrivateKey: PrivateKey,
        recipientX25519PrivateKey: PrivateKey,
    ): ByteArray {
        val buf = ByteBuffer.wrap(sealed)
        val kemCtLen = buf.short.toInt() and 0xffff
        val kemCt = ByteArray(kemCtLen)
        buf.get(kemCt)
        val ephLen = buf.short.toInt() and 0xffff
        val ephPub = ByteArray(ephLen)
        buf.get(ephPub)
        val nonce = ByteArray(GCM_NONCE_LENGTH)
        buf.get(nonce)
        val ciphertext = ByteArray(buf.remaining())
        buf.get(ciphertext)

        val kemSecret = PostQuantum.decapsulate(recipientKemPrivateKey, kemCt).encoded
        val x25519Secret = agree(recipientX25519PrivateKey, decodeX25519Public(ephPub))
        val aesKey = ByteArray(32)
        try {
            val combined = Hashing.sha256(x25519Secret, kemSecret)
            System.arraycopy(combined, 0, aesKey, 0, 32)
            SecureBytes.wipe(combined)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            return cipher.doFinal(ciphertext)
        } finally {
            SecureBytes.wipeAll(aesKey, x25519Secret, kemSecret)
        }
    }

    fun sealWithRawKey(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        val nonce = ByteArray(GCM_NONCE_LENGTH)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    fun openWithRawKey(sealed: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(sealed.size > GCM_NONCE_LENGTH) { "sealed blob too short" }
        val nonce = sealed.copyOfRange(0, GCM_NONCE_LENGTH)
        val ciphertext = sealed.copyOfRange(GCM_NONCE_LENGTH, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Vault packs Shamir ordinate + Feldman scalar before sealing.
     * @see org.finix.vault.application.usecase.SplitMasterKeyUseCase.packSharePayload
     */
    fun packSharePayload(shamirY: ByteArray, feldmanY: ByteArray): ByteArray {
        require(feldmanY.size == FELDMAN_SCALAR_BYTES) {
            "Feldman ordinate must be $FELDMAN_SCALAR_BYTES bytes"
        }
        return ByteBuffer.allocate(2 + shamirY.size + feldmanY.size)
            .putShort(shamirY.size.toShort())
            .put(shamirY)
            .put(feldmanY)
            .array()
    }

    fun unpackSharePayload(payload: ByteArray): Pair<ByteArray, ByteArray> {
        val buf = ByteBuffer.wrap(payload)
        val shamirLen = buf.short.toInt() and 0xffff
        val shamirY = ByteArray(shamirLen)
        buf.get(shamirY)
        val feldmanY = ByteArray(buf.remaining())
        buf.get(feldmanY)
        return shamirY to feldmanY
    }

    const val FELDMAN_SCALAR_BYTES: Int = 32

    private fun agree(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance(X25519, PROVIDER)
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }
}
