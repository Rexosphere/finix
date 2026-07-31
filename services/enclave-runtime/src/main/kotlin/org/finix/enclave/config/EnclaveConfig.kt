package org.finix.enclave.config

import org.finix.enclave.domain.crypto.HybridSeal
import org.finix.kernel.crypto.PqcCodec
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64

/**
 * Loads build-time key material from classpath. Keys are demo fixtures shared with vault via
 * `infra/enclave/` public `.b64` files — vault seals to these publics after verifying attestation.
 */
@Configuration
@EnableConfigurationProperties(EnclaveProperties::class)
class EnclaveConfig(
    private val properties: EnclaveProperties,
    private val resourceLoader: ResourceLoader,
) {

    @Bean
    fun clock(): java.time.Clock = java.time.Clock.systemUTC()

    @Bean
    fun enclaveKeyMaterial(): EnclaveKeyMaterial = EnclaveKeyMaterial(
        moduleId = properties.moduleId,
        attestationSigningPrivate = PqcCodec.decodeSigningPrivateKey(readB64(properties.attestationSigningPrivate)),
        attestationSigningPublic = PqcCodec.decodeSigningPublicKey(readB64(properties.attestationSigningPublic)),
        attestationPublicKeyB64 = readB64(properties.attestationSigningPublic),
        mlKemPrivate = PqcCodec.decodeKemPrivateKey(readB64(properties.mlkemPrivate)),
        mlKemPublicB64 = readB64(properties.mlkemPublic),
        x25519Private = HybridSeal.decodeX25519Private(Base64.getDecoder().decode(readB64(properties.x25519Private))),
        x25519PublicB64 = readB64(properties.x25519Public),
    )

    private fun readB64(location: String): String =
        resourceLoader.getResource(location).inputStream.bufferedReader().use { it.readText().trim() }
}

data class EnclaveKeyMaterial(
    val moduleId: String,
    val attestationSigningPrivate: PrivateKey,
    val attestationSigningPublic: PublicKey,
    val attestationPublicKeyB64: String,
    val mlKemPrivate: PrivateKey,
    val mlKemPublicB64: String,
    val x25519Private: PrivateKey,
    val x25519PublicB64: String,
)
