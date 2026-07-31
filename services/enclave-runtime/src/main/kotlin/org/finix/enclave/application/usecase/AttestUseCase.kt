package org.finix.enclave.application.usecase

import org.finix.enclave.config.EnclaveKeyMaterial
import org.finix.enclave.domain.AttestationDocument
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
class AttestUseCase(
    private val keys: EnclaveKeyMaterial,
    private val clock: Clock,
) {
    fun execute(nonce: String? = null): AttestationDocument =
        AttestationDocument.sign(
            moduleId = keys.moduleId,
            timestamp = Instant.now(clock),
            pcrs = AttestationDocument.DEMO_PCRS,
            mlKemPublicB64 = keys.mlKemPublicB64,
            x25519PublicB64 = keys.x25519PublicB64,
            attestationPublicB64 = keys.attestationPublicKeyB64,
            signingPrivate = keys.attestationSigningPrivate,
            nonce = nonce,
        )
}
