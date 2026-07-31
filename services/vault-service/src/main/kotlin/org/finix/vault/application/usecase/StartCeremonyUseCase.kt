package org.finix.vault.application.usecase

import org.finix.vault.application.port.CeremonyRepository
import org.finix.vault.domain.Ceremony
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Start a ceremony: if sealed material already exists, return it; otherwise run a fresh split.
 */
@Service
class StartCeremonyUseCase(
    private val ceremonies: CeremonyRepository,
    private val splitMasterKey: SplitMasterKeyUseCase,
) {
    @Transactional
    fun execute(): Ceremony {
        val existing = ceremonies.findLatest()
        if (existing != null && ceremonies.findShards(existing.id).size == 5) {
            return existing
        }
        return splitMasterKey.execute()
    }
}
