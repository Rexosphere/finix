package org.finix.ledger.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.finix.kernel.crypto.CanonicalJson
import org.springframework.stereotype.Component

/**
 * Turns the domain's plain [Map] payload into RFC 8785 JCS bytes for [org.finix.kernel.crypto.Hashing.chain].
 *
 * Lives in the application layer so the domain stays free of Jackson (ArchUnit + property tests).
 */
@Component
class LedgerCanonicalizer(
    private val mapper: ObjectMapper,
) {
    fun bytes(payload: Map<String, Any?>): ByteArray =
        CanonicalJson.canonicalBytes(mapper.valueToTree(payload))
}
