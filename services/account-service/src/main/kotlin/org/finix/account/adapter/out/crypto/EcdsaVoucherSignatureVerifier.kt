package org.finix.account.adapter.out.crypto

import io.github.oshai.kotlinlogging.KotlinLogging
import org.finix.account.application.port.VoucherSignatureVerifier
import org.springframework.stereotype.Component
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

private val log = KotlinLogging.logger {}

/**
 * Verifies offline voucher signatures produced by the PWA's non-extractable WebCrypto key
 * (`apps/web/js/offline.js`): ECDSA on P-256 with SHA-256, public key exported as SPKI.
 *
 * WebCrypto emits IEEE-P1363 signatures (raw `r || s`), while the JCA default expects DER, so
 * the encoding is detected rather than assumed — a valid voucher rejected on encoding alone
 * would quarantine an honest device.
 */
@Component
class EcdsaVoucherSignatureVerifier : VoucherSignatureVerifier {

    override fun verify(publicKeySpki: ByteArray, payload: ByteArray, signatureDer: ByteArray): Boolean =
        try {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeySpki))
            val algorithm = if (isDerEncoded(signatureDer)) DER_ALGORITHM else P1363_ALGORITHM
            Signature.getInstance(algorithm).run {
                initVerify(publicKey)
                update(payload)
                verify(signatureDer)
            }
        } catch (ex: GeneralSecurityException) {
            // A malformed key or signature is a rejected voucher, not a server fault.
            log.warn { "offline voucher signature verification failed: ${ex.message}" }
            false
        } catch (ex: IllegalArgumentException) {
            log.warn { "offline voucher signature is malformed: ${ex.message}" }
            false
        }

    private fun isDerEncoded(signature: ByteArray): Boolean =
        signature.size > 1 && signature[0] == DER_SEQUENCE_TAG

    private companion object {
        const val DER_ALGORITHM = "SHA256withECDSA"
        const val P1363_ALGORITHM = "SHA256withECDSAinP1363Format"
        const val DER_SEQUENCE_TAG: Byte = 0x30
    }
}
