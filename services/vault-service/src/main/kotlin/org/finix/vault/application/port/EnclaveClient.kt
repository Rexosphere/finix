package org.finix.vault.application.port

/**
 * Client for the isolated enclave runtime: attest first, then reconstruct.
 *
 * Reconstruction returns network-config plaintext and an egress log proving the Master Key
 * never crossed the enclave boundary.
 */
interface EnclaveClient {
    fun attest(): org.finix.vault.application.AttestationDoc

    fun reconstruct(
        sealedShares: List<ByteArray>,
        commitments: List<ByteArray>,
        sealedNetworkConfig: ByteArray,
    ): org.finix.vault.application.ReconstructResult
}
