package org.finix.vault.application.usecase

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.vault.application.port.CeremonyRepository
import org.finix.vault.domain.Ceremony
import org.finix.vault.domain.CustodianId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Record a custodian approval. When approvals reach the ceremony threshold the state becomes
 * [org.finix.vault.domain.CeremonyState.THRESHOLD_MET].
 */
@Service
class ApproveCeremonyUseCase(
    private val ceremonies: CeremonyRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun execute(custodianId: CustodianId): Ceremony {
        val ceremony = ceremonies.findLatest()
            ?: throw DomainException(DomainError.NotFound("Ceremony", "latest"))
        ceremony.approve(custodianId, Instant.now(clock))
        return ceremonies.save(ceremony)
    }
}
