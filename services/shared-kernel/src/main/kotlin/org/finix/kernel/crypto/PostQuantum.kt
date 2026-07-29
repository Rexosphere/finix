package org.finix.kernel.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KEM
import javax.crypto.SecretKey

/**
 * Post-quantum primitives for the 2065 threat model, per blueprint §2.2.3 and §6.1.
 *
 * Concretely: **ML-KEM-768** (FIPS 203, the standardised CRYSTALS-Kyber) to seal Master Key
 * shards to the enclave, and **ML-DSA-65** (FIPS 204, standardised CRYSTALS-Dilithium) to sign
 * ledger anchors. Both come from BouncyCastle's JCA provider; ML-KEM is driven through the
 * JDK 21 `javax.crypto.KEM` API rather than a BC-specific type, so the code stays portable.
 *
 * Key exchange is **hybrid**: an X25519 shared secret is concatenated with the ML-KEM shared
 * secret before the KDF (see `HybridSeal` in vault-service). That is the transition posture NIST recommends — the result
 * is no weaker than X25519 against a classical attacker even if ML-KEM is later broken, and no
 * weaker than ML-KEM against a quantum one.
 */
object PostQuantum {

    const val KEM_ALGORITHM: String = "ML-KEM-768"
    const val SIGNATURE_ALGORITHM: String = "ML-DSA-65"
    internal const val PROVIDER = "BC"

    init {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val random = SecureRandom()

    // ---------------------------------------------------------------- ML-KEM (encryption)

    // The algorithm name already pins the parameter set, so calling initialize() with a null
    // spec is both unnecessary and rejected by BouncyCastle.
    fun generateKemKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(KEM_ALGORITHM, PROVIDER).generateKeyPair()

    /** Encapsulates a fresh shared secret to [recipientPublicKey]. */
    fun encapsulate(recipientPublicKey: PublicKey): Encapsulation {
        val encapsulated = KEM.getInstance("ML-KEM", PROVIDER)
            .newEncapsulator(recipientPublicKey)
            .encapsulate()
        return Encapsulation(
            ciphertext = encapsulated.encapsulation(),
            sharedSecret = encapsulated.key(),
        )
    }

    fun decapsulate(recipientPrivateKey: PrivateKey, ciphertext: ByteArray): SecretKey =
        KEM.getInstance("ML-KEM", PROVIDER)
            .newDecapsulator(recipientPrivateKey)
            .decapsulate(ciphertext)

    data class Encapsulation(val ciphertext: ByteArray, val sharedSecret: SecretKey) {
        // ByteArray in a data class needs structural equality spelled out.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Encapsulation &&
                ciphertext.contentEquals(other.ciphertext) &&
                sharedSecret == other.sharedSecret)

        override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + sharedSecret.hashCode()
    }

    // ---------------------------------------------------------------- ML-DSA (signatures)

    fun generateSigningKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(SIGNATURE_ALGORITHM, PROVIDER).generateKeyPair()

    fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM, PROVIDER).run {
            initSign(privateKey, random)
            update(message)
            sign()
        }

    fun verify(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            Signature.getInstance(SIGNATURE_ALGORITHM, PROVIDER).run {
                initVerify(publicKey)
                update(message)
                verify(signature)
            }
        }.getOrDefault(false)
}


/**
 * Base64 (de)serialisation for post-quantum keys, kept separate from the algorithm operations
 * so that persistence concerns never leak into [PostQuantum].
 */
object PqcCodec {

    fun encodePublicKey(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)

    fun encodePrivateKey(key: PrivateKey): String = Base64.getEncoder().encodeToString(key.encoded)

    fun decodeKemPublicKey(base64: String): PublicKey =
        KeyFactory.getInstance(PostQuantum.KEM_ALGORITHM, PostQuantum.PROVIDER)
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

    fun decodeKemPrivateKey(base64: String): PrivateKey =
        KeyFactory.getInstance(PostQuantum.KEM_ALGORITHM, PostQuantum.PROVIDER)
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))

    fun decodeSigningPublicKey(base64: String): PublicKey =
        KeyFactory.getInstance(PostQuantum.SIGNATURE_ALGORITHM, PostQuantum.PROVIDER)
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

    fun decodeSigningPrivateKey(base64: String): PrivateKey =
        KeyFactory.getInstance(PostQuantum.SIGNATURE_ALGORITHM, PostQuantum.PROVIDER)
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))
}
