package org.finix.enclave.application

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.finix.enclave.application.usecase.AttestUseCase
import org.finix.enclave.config.EnclaveKeyMaterial
import org.finix.enclave.domain.AttestationDocument
import org.finix.enclave.domain.crypto.HybridSeal
import org.finix.kernel.crypto.PqcCodec
import org.finix.kernel.crypto.PostQuantum
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class AttestUseCaseTest : StringSpec({

    "attest produces a document vault can verify" {
        val signing = PostQuantum.generateSigningKeyPair()
        val kem = PostQuantum.generateKemKeyPair()
        val x = HybridSeal.generateX25519KeyPair()
        val keys = EnclaveKeyMaterial(
            moduleId = "finix-enclave-1",
            attestationSigningPrivate = signing.private,
            attestationSigningPublic = signing.public,
            attestationPublicKeyB64 = PqcCodec.encodePublicKey(signing.public),
            mlKemPrivate = kem.private,
            mlKemPublicB64 = PqcCodec.encodePublicKey(kem.public),
            x25519Private = x.private,
            x25519PublicB64 = Base64.getEncoder().encodeToString(x.public.encoded),
        )
        val clock = Clock.fixed(Instant.parse("2026-07-30T15:00:00Z"), ZoneOffset.UTC)
        val doc = AttestUseCase(keys, clock).execute("n-1")
        doc.moduleId shouldBe "finix-enclave-1"
        doc.nonce shouldBe "n-1"
        AttestationDocument.verify(doc, signing.public) shouldBe true
    }
})
