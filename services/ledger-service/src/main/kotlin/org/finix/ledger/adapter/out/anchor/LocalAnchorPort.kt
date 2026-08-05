package org.finix.ledger.adapter.out.anchor

import io.github.oshai.kotlinlogging.KotlinLogging
import org.finix.ledger.application.port.AnchorPort
import org.finix.ledger.domain.LedgerAnchor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * Default `finix.anchor.adapter=local` publication: the anchor is already signed with ML-DSA-65
 * by the use case, so "publishing" locally means accepting it for persistence unchanged.
 *
 * The Fabric adapter (compose `--profile fabric`) would replace this bean and return an anchor
 * carrying the distributed-ledger receipt — which is why the port returns a [LedgerAnchor]
 * rather than nothing.
 */
@Component
@ConditionalOnProperty(prefix = "finix.anchor", name = ["adapter"], havingValue = "local", matchIfMissing = true)
class LocalAnchorPort : AnchorPort {

    override fun publish(anchor: LedgerAnchor): LedgerAnchor {
        log.debug {
            "Anchoring window ${anchor.windowStartSeq}..${anchor.windowEndSeq} " +
                "(${anchor.entryCount} entries) at root ${anchor.merkleRoot}"
        }
        return anchor
    }
}
