package org.finix.vault.application.port

/**
 * Enclave public keys used to hybrid-seal Master Key shards.
 */
interface EnclaveKeyPort {
    fun kemPublicKeyEncoded(): ByteArray
    fun x25519PublicKeyEncoded(): ByteArray
}
