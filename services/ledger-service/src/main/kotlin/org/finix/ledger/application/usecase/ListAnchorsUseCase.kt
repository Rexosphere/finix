package org.finix.ledger.application.usecase

import org.finix.ledger.application.port.AnchorRepository
import org.finix.ledger.domain.LedgerAnchor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ListAnchorsUseCase(
    private val anchors: AnchorRepository,
) {
    @Transactional(readOnly = true)
    fun execute(): List<LedgerAnchor> = anchors.findAll()
}
