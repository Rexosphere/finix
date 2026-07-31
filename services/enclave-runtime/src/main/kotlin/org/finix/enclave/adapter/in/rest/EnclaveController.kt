package org.finix.enclave.adapter.`in`.rest

import org.finix.enclave.application.usecase.AttestUseCase
import org.finix.enclave.application.usecase.ReconstructUseCase
import org.finix.enclave.domain.AttestationDocument
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Reconstruct-only surface. Vault calls these over the compose network (port 8090).
 *
 * HybridSeal blobs are vault's packed format — see [org.finix.enclave.domain.crypto.HybridSeal].
 */
@RestController
class EnclaveController(
    private val attest: AttestUseCase,
    private val reconstruct: ReconstructUseCase,
) {

    @PostMapping("/attest")
    fun attest(@RequestBody(required = false) body: AttestRequest?): AttestResponse {
        val doc = attest.execute(body?.nonce)
        return AttestResponse.from(doc)
    }

    @PostMapping("/reconstruct", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun reconstruct(@RequestBody body: ReconstructRequest): ReconstructResponse {
        val result = reconstruct.execute(body.toCommand())
        return ReconstructResponse(
            networkConfig = result.networkConfig,
            egressLog = result.egressLog,
        )
    }
}

data class AttestRequest(val nonce: String? = null)

data class AttestResponse(
    val format: String,
    val module_id: String,
    val timestamp: String,
    val pcrs: Map<String, String>,
    val public_key_mlkem_b64: String,
    val public_key_x25519_b64: String,
    val attestation_public_key_b64: String,
    val nonce: String?,
    val signature_b64: String,
) {
    companion object {
        fun from(doc: AttestationDocument) = AttestResponse(
            format = doc.format,
            module_id = doc.moduleId,
            timestamp = doc.timestamp.toString(),
            pcrs = doc.pcrs,
            public_key_mlkem_b64 = doc.publicKeyMlkemB64,
            public_key_x25519_b64 = doc.publicKeyX25519B64,
            attestation_public_key_b64 = doc.attestationPublicKeyB64,
            nonce = doc.nonce,
            signature_b64 = doc.signatureB64,
        )
    }
}

/**
 * ```
 * {
 *   "sealedShares": [{"x":1,"sealedB64":"<HybridSeal packed blob>"}],
 *   "commitmentsB64": ["..."],
 *   "sealedNetworkConfigB64": "<nonce||ciphertext under master key>"
 * }
 * ```
 */
data class ReconstructRequest(
    val sealedShares: List<SealedShareDto>,
    val commitmentsB64: List<String> = emptyList(),
    val sealedNetworkConfigB64: String,
) {
    fun toCommand() = ReconstructUseCase.Command(
        sealedShares = sealedShares.map {
            ReconstructUseCase.SealedShareCommand(x = it.x, sealedB64 = it.sealedB64)
        },
        commitmentsB64 = commitmentsB64,
        sealedNetworkConfigB64 = sealedNetworkConfigB64,
    )
}

data class SealedShareDto(
    val x: Int,
    val sealedB64: String,
)

data class ReconstructResponse(
    val networkConfig: String,
    val egressLog: List<String>,
)
