package org.finix.ledger.application.usecase

import org.finix.ledger.application.port.LedgerRepository
import org.finix.ledger.domain.VerificationReport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Walk the entire hash chain and report the first break, if any. */
@Service
class VerifyLedgerUseCase(
    private val ledgerRepository: LedgerRepository,
) {
    @Transactional(readOnly = true)
    operator fun invoke(): VerificationReport = ledgerRepository.verifyChain()
}
