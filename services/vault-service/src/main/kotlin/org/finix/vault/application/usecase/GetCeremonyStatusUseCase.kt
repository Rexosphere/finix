package org.finix.vault.application.usecase

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.finix.vault.application.CeremonyStatus
import org.finix.vault.application.port.CeremonyRepository
import org.finix.vault.domain.CustodianId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GetCeremonyStatusUseCase(
    private val ceremonies: CeremonyRepository,
) {
    @Transactional(readOnly = true)
    fun execute(): CeremonyStatus {
        val ceremony = ceremonies.findLatest()
            ?: throw DomainException(DomainError.NotFound("Ceremony", "latest"))
        val shards = ceremonies.findShards(ceremony.id)
        return CeremonyStatus(
            ceremony = ceremony,
            shardCount = shards.size,
            custodians = CustodianId.ALL,
        )
    }
}
