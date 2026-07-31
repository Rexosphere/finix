package org.finix.vault.application

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
 * Hybrid seal: X25519 + ML-KEM-768 shared secrets are concatenated, SHA-256-KDF'd, and used as
 * an AES-256-GCM key — the NIST transition posture referenced from [PostQuantum].
 *
 * Wire format (big-endian lengths):
 * ```
 * kemCtLen(2) || kemCt || x25519EphemeralPubLen(2) || x25519EphemeralPub || nonce(12) || ciphertext+tag
 * ```
 *
 * Decryption is intended for the enclave path only; vault-service seals shards and never opens them
 * in the happy path.
 */
object HybridSeal {

    private const val GCM_NONCE_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val X25519 = "X25519"

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val random = SecureRandom()

    fun generateX25519KeyPair(): KeyPair =
        KeyPairGenerator.getInstance(X25519, "BC").generateKeyPair()

    fun encodePublicKey(key: PublicKey): ByteArray = key.encoded

    fun encodePrivateKey(key: PrivateKey): ByteArray = key.encoded

    fun decodeX25519PublicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance(X25519, "BC")
            .generatePublic(X509EncodedKeySpec(encoded))

    fun decodeX25519PrivateKey(encoded: ByteArray): PrivateKey =
        KeyFactory.getInstance(X25519, "BC")
            .generatePrivate(PKCS8EncodedKeySpec(encoded))

    /**
     * Seal [plaintext] to the recipient's ML-KEM and X25519 public keys.
     * Caller must [ByteArray.fill] [plaintext] after use when it held key material.
     */
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
            combined.fill(0)
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
            aesKey.fill(0)
            x25519Secret.fill(0)
            kemSecret.fill(0)
        }
    }

    /** Open a blob produced by [seal]. Enclave-only in the ceremony happy path. */
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
        val x25519Secret = agree(recipientX25519PrivateKey, decodeX25519PublicKey(ephPub))
        val aesKey = ByteArray(32)
        try {
            val combined = Hashing.sha256(x25519Secret, kemSecret)
            System.arraycopy(combined, 0, aesKey, 0, 32)
            combined.fill(0)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            return cipher.doFinal(ciphertext)
        } finally {
            aesKey.fill(0)
            x25519Secret.fill(0)
            kemSecret.fill(0)
        }
    }

    /** AES-256-GCM with a raw 32-byte key (used to wrap network-config under the Master Key). */
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

    private fun agree(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance(X25519, "BC")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }
}
