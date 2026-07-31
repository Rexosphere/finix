package org.finix.enclave.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.finix.enclave.domain.crypto.HybridSeal
import org.finix.enclave.domain.crypto.SecureBytes
import org.finix.enclave.domain.crypto.ShamirSplit
import org.finix.kernel.crypto.PqcCodec
import org.finix.kernel.crypto.PostQuantum
import java.util.Base64

class ReconstructSessionTest : StringSpec({

    "reconstruct known 3-of-5 sealed shares decrypts config and wipes key material" {
        SecureBytes.resetWipeCounter()

        val masterKey = ByteArray(32) { (it + 1).toByte() }
        val networkConfig = """{"network":"finix-demo","unlocked":true}"""
        val sealedConfig = HybridSeal.sealWithRawKey(networkConfig.toByteArray(), masterKey)

        val mlKem = PostQuantum.generateKemKeyPair()
        val x25519 = HybridSeal.generateX25519KeyPair()
        val shares = ShamirSplit.split(masterKey, n = 5, k = 3)
        val sealedInputs = shares.take(3).map { share ->
            val feldmanPad = ByteArray(HybridSeal.FELDMAN_SCALAR_BYTES) { 0x11 }
            val payload = HybridSeal.packSharePayload(share.y, feldmanPad)
            try {
                val sealed = HybridSeal.seal(payload, mlKem.public, x25519.public)
                ReconstructSession.SealedShareInput(share.x, sealed)
            } finally {
                SecureBytes.wipe(payload)
                SecureBytes.wipe(feldmanPad)
            }
        }
        shares.forEach { SecureBytes.wipe(it.y) }
        SecureBytes.wipe(masterKey)

        val before = SecureBytes.wipeInvocations
        val result = ReconstructSession.run(
            sealedShares = sealedInputs,
            sealedNetworkConfig = sealedConfig,
            mlKemPrivate = mlKem.private,
            x25519Private = x25519.private,
        )

        result.networkConfig shouldBe networkConfig
        result.egressLog shouldContain "master-key zeroed"
        result.egressLog.any { it.startsWith("decrypted network-config") } shouldBe true
        SecureBytes.wipeInvocations shouldBeGreaterThan before
    }

    "HybridSeal round-trips and foreign ML-KEM key fails open" {
        val recipient = PostQuantum.generateKemKeyPair()
        val x = HybridSeal.generateX25519KeyPair()
        val plain = "shard-bytes".toByteArray()
        val sealed = HybridSeal.seal(plain, recipient.public, x.public)
        HybridSeal.open(sealed, recipient.private, x.private).contentEquals(plain) shouldBe true

        val impostor = PostQuantum.generateKemKeyPair()
        val opened = runCatching { HybridSeal.open(sealed, impostor.private, x.private) }
        opened.isFailure shouldBe true
    }

    "raw key and payload helpers reject bad inputs" {
        shouldThrow<IllegalArgumentException> {
            HybridSeal.sealWithRawKey(byteArrayOf(1), ByteArray(16))
        }
        shouldThrow<IllegalArgumentException> {
            HybridSeal.openWithRawKey(ByteArray(4), ByteArray(32))
        }
        shouldThrow<IllegalArgumentException> {
            HybridSeal.packSharePayload(byteArrayOf(1), ByteArray(8))
        }
        val y = byteArrayOf(1, 2, 3)
        val feldman = ByteArray(HybridSeal.FELDMAN_SCALAR_BYTES) { 1 }
        val packed = HybridSeal.packSharePayload(y, feldman)
        val (outY, outF) = HybridSeal.unpackSharePayload(packed)
        outY.contentEquals(y) shouldBe true
        outF.contentEquals(feldman) shouldBe true
    }
})

class AttestationDocumentTest : StringSpec({

    "attestation signature verifies under the signing public key" {
        val signing = PostQuantum.generateSigningKeyPair()
        val mlKemB64 = PqcCodec.encodePublicKey(PostQuantum.generateKemKeyPair().public)
        val x25519B64 = Base64.getEncoder().encodeToString(HybridSeal.generateX25519KeyPair().public.encoded)
        val doc = AttestationDocument.sign(
            moduleId = "finix-enclave-1",
            timestamp = java.time.Instant.parse("2026-07-30T12:00:00Z"),
            pcrs = AttestationDocument.DEMO_PCRS,
            mlKemPublicB64 = mlKemB64,
            x25519PublicB64 = x25519B64,
            attestationPublicB64 = PqcCodec.encodePublicKey(signing.public),
            signingPrivate = signing.private,
            nonce = "vault-nonce-1",
        )
        AttestationDocument.verify(doc, signing.public) shouldBe true

        val tampered = doc.copy(moduleId = "other")
        AttestationDocument.verify(tampered, signing.public) shouldBe false
    }
})
