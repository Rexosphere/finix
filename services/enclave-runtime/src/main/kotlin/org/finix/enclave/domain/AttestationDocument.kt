package org.finix.enclave.domain

import org.finix.kernel.crypto.PostQuantum
import java.security.PrivateKey
import java.security.PublicKey
import java.time.Instant
import java.util.Base64

/**
 * Mock AWS Nitro Enclave attestation document. Signed with the build-time ML-DSA key so vault
 * can verify `signature_b64` over the canonical payload before releasing sealed shares.
 */
data class AttestationDocument(
    val format: String = FORMAT,
    val moduleId: String,
    val timestamp: Instant,
    val pcrs: Map<String, String>,
    val publicKeyMlkemB64: String,
    val publicKeyX25519B64: String,
    val attestationPublicKeyB64: String,
    val nonce: String?,
    val signatureB64: String,
) {
    companion object {
        const val FORMAT = "nitro-enclave-doc-mock"

        /** Fixed demo PCR digests — stand-ins for measured boot in a real Nitro enclave. */
        val DEMO_PCRS: Map<String, String> = mapOf(
            "0" to "a".repeat(64),
            "1" to "b".repeat(64),
            "2" to "c".repeat(64),
        )

        /**
         * Canonical bytes covered by [signatureB64]. Field order is fixed; vault must hash the
         * same UTF-8 string before [PostQuantum.verify].
         */
        fun canonicalPayload(
            moduleId: String,
            timestamp: Instant,
            pcrs: Map<String, String>,
            publicKeyMlkemB64: String,
            publicKeyX25519B64: String,
            nonce: String?,
        ): ByteArray {
            val pcrPart = listOf("0", "1", "2").joinToString("|") { id -> "$id=${pcrs.getValue(id)}" }
            val noncePart = nonce ?: ""
            return listOf(
                FORMAT,
                moduleId,
                timestamp.toString(),
                pcrPart,
                publicKeyMlkemB64,
                publicKeyX25519B64,
                noncePart,
            ).joinToString("\n").toByteArray(Charsets.UTF_8)
        }

        fun sign(
            moduleId: String,
            timestamp: Instant,
            pcrs: Map<String, String>,
            mlKemPublicB64: String,
            x25519PublicB64: String,
            attestationPublicB64: String,
            signingPrivate: PrivateKey,
            nonce: String?,
        ): AttestationDocument {
            val payload = canonicalPayload(moduleId, timestamp, pcrs, mlKemPublicB64, x25519PublicB64, nonce)
            val signature = PostQuantum.sign(signingPrivate, payload)
            return AttestationDocument(
                moduleId = moduleId,
                timestamp = timestamp,
                pcrs = pcrs,
                publicKeyMlkemB64 = mlKemPublicB64,
                publicKeyX25519B64 = x25519PublicB64,
                attestationPublicKeyB64 = attestationPublicB64,
                nonce = nonce,
                signatureB64 = Base64.getEncoder().encodeToString(signature),
            )
        }

        fun verify(doc: AttestationDocument, attestationPublic: PublicKey): Boolean {
            val payload = canonicalPayload(
                doc.moduleId,
                doc.timestamp,
                doc.pcrs,
                doc.publicKeyMlkemB64,
                doc.publicKeyX25519B64,
                doc.nonce,
            )
            val signature = Base64.getDecoder().decode(doc.signatureB64)
            return PostQuantum.verify(attestationPublic, payload, signature)
        }
    }
}
