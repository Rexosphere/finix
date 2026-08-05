package org.finix.vault.adapter.out.enclave

import com.fasterxml.jackson.annotation.JsonProperty
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.vault.application.AttestationDoc
import org.finix.vault.application.ReconstructResult
import org.finix.vault.application.port.EnclaveClient
import org.finix.vault.config.EnclaveProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Instant
import java.util.Base64

/**
 * Talks to enclave-runtime over the compose network (`POST /attest`, `POST /reconstruct`).
 *
 * Everything crossing this boundary is Base64 ciphertext in one direction and network-config
 * plaintext in the other. The enclave's response shape is snake_case (it mimics a Nitro
 * attestation document), so the wire names are pinned rather than derived.
 */
@Component
class HttpEnclaveClient(
    builder: RestClient.Builder,
    properties: EnclaveProperties,
) : EnclaveClient {

    private val client: RestClient = builder.baseUrl(properties.baseUrl).build()

    override fun attest(): AttestationDoc {
        val response = call("attest") {
            client.post()
                .uri("/attest")
                .body(AttestBody(nonce = null))
                .retrieve()
                .body(AttestResult::class.java)
        } ?: DomainError.Unavailable(DEPENDENCY, "enclave returned an empty attestation").raise()

        val signature = decodeOrEmpty(response.signatureB64)
        return AttestationDoc(
            moduleId = response.moduleId,
            timestamp = parseTimestamp(response.timestamp),
            // PCR0 is the measured-boot digest a real Nitro verifier would pin the enclave to.
            digest = response.pcrs["0"].orEmpty(),
            signature = signature,
            // A signed document is the only thing the enclave will not produce if its key
            // material is missing, so signature presence is the demo's validity check.
            valid = signature.isNotEmpty(),
        )
    }

    override fun reconstruct(
        sealedShares: List<ByteArray>,
        commitments: List<ByteArray>,
        sealedNetworkConfig: ByteArray,
    ): ReconstructResult {
        val encoder = Base64.getEncoder()
        // Shares arrive ordered by their Shamir evaluation point, which starts at 1.
        val body = ReconstructBody(
            sealedShares = sealedShares.mapIndexed { index, blob ->
                SealedShareBody(x = index + 1, sealedB64 = encoder.encodeToString(blob))
            },
            commitmentsB64 = commitments.map { encoder.encodeToString(it) },
            sealedNetworkConfigB64 = encoder.encodeToString(sealedNetworkConfig),
        )

        val response = call("reconstruct") {
            client.post()
                .uri("/reconstruct")
                .body(body)
                .retrieve()
                .body(ReconstructResponseBody::class.java)
        } ?: DomainError.Unavailable(DEPENDENCY, "enclave returned an empty reconstruct result").raise()

        return ReconstructResult(
            networkConfigPlaintext = response.networkConfig,
            egressLog = response.egressLog,
        )
    }

    private fun <T> call(operation: String, block: () -> T): T =
        try {
            block()
        } catch (ex: RestClientException) {
            throw DomainException(
                DomainError.Unavailable(DEPENDENCY, "enclave $operation failed: ${ex.message}"),
                ex,
            )
        }

    private fun decodeOrEmpty(base64: String?): ByteArray =
        base64?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrDefault(ByteArray(0)) }
            ?: ByteArray(0)

    private fun parseTimestamp(raw: String?): Long =
        raw?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    private companion object {
        const val DEPENDENCY = "enclave"
    }
}

internal data class AttestBody(val nonce: String?)

/** Mirrors enclave-runtime `AttestResponse`; the key material fields are not needed here. */
internal data class AttestResult(
    @param:JsonProperty("module_id")
    val moduleId: String = "",
    val timestamp: String? = null,
    val pcrs: Map<String, String> = emptyMap(),
    @param:JsonProperty("signature_b64")
    val signatureB64: String? = null,
)

/** Mirrors enclave-runtime `ReconstructRequest`. */
internal data class ReconstructBody(
    val sealedShares: List<SealedShareBody>,
    val commitmentsB64: List<String>,
    val sealedNetworkConfigB64: String,
)

internal data class SealedShareBody(
    val x: Int,
    val sealedB64: String,
)

/** Mirrors enclave-runtime `ReconstructResponse`. */
internal data class ReconstructResponseBody(
    val networkConfig: String = "",
    val egressLog: List<String> = emptyList(),
)
