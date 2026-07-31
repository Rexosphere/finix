package org.finix.enclave.domain

import org.finix.enclave.domain.crypto.HybridSeal
import org.finix.enclave.domain.crypto.SecureBytes
import org.finix.enclave.domain.crypto.Shamir
import java.security.PrivateKey

/**
 * Reconstruct-only domain operation: open packed HybridSeal blobs, unpack Shamir ordinates,
 * interpolate the master key, decrypt the network-config blob, then zero every key buffer.
 *
 * The master key never becomes a [String] and never appears in [ReconstructResult].
 */
object ReconstructSession {

    data class SealedShareInput(
        val x: Int,
        val sealedBlob: ByteArray,
    )

    data class ReconstructResult(
        val networkConfig: String,
        val egressLog: List<String>,
    )

    fun run(
        sealedShares: List<SealedShareInput>,
        sealedNetworkConfig: ByteArray,
        mlKemPrivate: PrivateKey,
        x25519Private: PrivateKey,
    ): ReconstructResult {
        require(sealedShares.size >= 2) { "need at least 2 sealed shares" }

        val plaintextShares = ArrayList<Shamir.Share>(sealedShares.size)
        var masterKey: ByteArray? = null
        var configBytes: ByteArray? = null
        val payloadsToWipe = ArrayList<ByteArray>()
        val egress = mutableListOf<String>()

        try {
            for (sealed in sealedShares) {
                val payload = HybridSeal.open(sealed.sealedBlob, mlKemPrivate, x25519Private)
                payloadsToWipe += payload
                val (shamirY, feldmanY) = HybridSeal.unpackSharePayload(payload)
                SecureBytes.wipe(feldmanY)
                plaintextShares += Shamir.Share(sealed.x, shamirY)
            }
            egress += "opened ${plaintextShares.size} sealed shares"

            masterKey = Shamir.reconstruct(plaintextShares)
            egress += "shamir reconstruct (${masterKey.size} bytes)"

            configBytes = HybridSeal.openWithRawKey(sealedNetworkConfig, masterKey)
            egress += "decrypted network-config (${configBytes.size} bytes)"

            val networkConfig = configBytes.decodeToString()
            return ReconstructResult(
                networkConfig = networkConfig,
                egressLog = egress.toList() + "master-key zeroed",
            )
        } finally {
            plaintextShares.forEach { SecureBytes.wipe(it.y) }
            payloadsToWipe.forEach { SecureBytes.wipe(it) }
            SecureBytes.wipe(masterKey)
            SecureBytes.wipe(configBytes)
        }
    }
}
