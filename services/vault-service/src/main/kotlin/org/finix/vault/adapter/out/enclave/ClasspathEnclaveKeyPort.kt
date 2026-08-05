package org.finix.vault.adapter.out.enclave

import org.finix.kernel.domain.DomainError
import org.finix.vault.application.port.EnclaveKeyPort
import org.finix.vault.config.EnclaveProperties
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * Supplies the enclave's public encryption keys so shards can be sealed *to* the enclave.
 *
 * Only public halves are ever available here; the matching private keys live exclusively in
 * enclave-runtime's own classpath, which is what makes "vault cannot open a shard" structural
 * rather than a matter of discipline.
 *
 * Configured Base64 wins over the bundled copies so a real deployment can point at the keys of
 * an actual attested enclave without rebuilding the image.
 */
@Component
class ClasspathEnclaveKeyPort(
    properties: EnclaveProperties,
) : EnclaveKeyPort {

    private val kemPublicKey: ByteArray =
        decode(properties.kemPublicKeyBase64.ifBlank { readResource(KEM_RESOURCE) }, "ML-KEM")

    private val x25519PublicKey: ByteArray =
        decode(properties.x25519PublicKeyBase64.ifBlank { readResource(X25519_RESOURCE) }, "X25519")

    override fun kemPublicKeyEncoded(): ByteArray = kemPublicKey.copyOf()

    override fun x25519PublicKeyEncoded(): ByteArray = x25519PublicKey.copyOf()

    private fun readResource(path: String): String {
        val resource = ClassPathResource(path)
        if (!resource.exists()) {
            DomainError.Invalid(
                detail = "enclave public key '$path' is missing from the classpath",
                properties = mapOf("resource" to path),
            ).raise()
        }
        return resource.inputStream.use { it.readBytes().decodeToString() }
    }

    private fun decode(base64: String, label: String): ByteArray {
        // The bundled files are PEM-style wrapped at 80 columns, so strip whitespace first.
        val compact = base64.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) {
            DomainError.Invalid("enclave $label public key is empty").raise()
        }
        return Base64.getDecoder().decode(compact)
    }

    private companion object {
        const val KEM_RESOURCE = "enclave/mlkem-public.b64"
        const val X25519_RESOURCE = "enclave/x25519-public.b64"
    }
}
