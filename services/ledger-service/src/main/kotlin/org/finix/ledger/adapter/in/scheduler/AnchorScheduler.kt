package org.finix.ledger.adapter.`in`.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import org.finix.ledger.application.usecase.AnchorWindowUseCase
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@Component
class AnchorScheduler(
    private val anchorWindow: AnchorWindowUseCase,
) {
    /** Every 60 seconds — blueprint §2.2.5 / M5 exit criterion. */
    @Scheduled(fixedDelayString = "\${finix.anchor.interval-ms:60000}")
    fun tick() {
        val anchor = anchorWindow.execute()
        if (anchor != null) {
            log.info { "Published ledger anchor ${anchor.id} covering ${anchor.windowStartSeq}..${anchor.windowEndSeq}" }
        }
    }
}
