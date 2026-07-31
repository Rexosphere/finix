package org.finix.vault.application.usecase

import org.finix.vault.application.port.CeremonyRepository
import org.finix.vault.domain.Ceremony
import org.finix.vault.domain.EgressLogEntry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Demo seed: wipe and recreate ceremony material when missing or explicitly requested.
 */
@Service
class SeedVaultUseCase(
    private val ceremonies: CeremonyRepository,
    private val splitMasterKey: SplitMasterKeyUseCase,
) {
    @Transactional
    fun execute(force: Boolean = false): Ceremony {
        val existing = ceremonies.findLatest()
        if (!force && existing != null && ceremonies.findShards(existing.id).isNotEmpty()) {
            return existing
        }
        ceremonies.deleteAll()
        return splitMasterKey.execute()
    }
}

@Service
class GetEgressLogUseCase(
    private val ceremonies: CeremonyRepository,
) {
    @Transactional(readOnly = true)
    fun execute(): List<EgressLogEntry> {
        val ceremony = ceremonies.findLatest() ?: return emptyList()
        return ceremonies.findEgressLog(ceremony.id)
    }
}
