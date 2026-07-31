package org.finix.ledger.config

import org.finix.kernel.crypto.PostQuantum
import org.finix.kernel.crypto.PqcCodec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.security.PrivateKey
import java.security.PublicKey
import java.time.Clock

@ConfigurationProperties(prefix = "finix.anchor")
data class AnchorProperties(
    val intervalMs: Long = 60_000,
    val adapter: String = "local",
    val signingPrivateKeyBase64: String = "",
    val signingPublicKeyBase64: String = "",
)

data class AnchorSigningKeys(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
) {
    val publicKeyBase64: String get() = PqcCodec.encodePublicKey(publicKey)
}

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AnchorProperties::class)
class LedgerConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun anchorSigningKeys(properties: AnchorProperties): AnchorSigningKeys {
        if (properties.signingPrivateKeyBase64.isNotBlank() && properties.signingPublicKeyBase64.isNotBlank()) {
            return AnchorSigningKeys(
                privateKey = PqcCodec.decodeSigningPrivateKey(properties.signingPrivateKeyBase64),
                publicKey = PqcCodec.decodeSigningPublicKey(properties.signingPublicKeyBase64),
            )
        }
        val pair = PostQuantum.generateSigningKeyPair()
        return AnchorSigningKeys(privateKey = pair.private, publicKey = pair.public)
    }
}
