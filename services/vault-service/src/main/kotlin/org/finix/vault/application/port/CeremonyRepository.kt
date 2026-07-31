package org.finix.vault.application.port

import org.finix.vault.domain.Ceremony
import org.finix.vault.domain.EgressLogEntry
import org.finix.vault.domain.SealedShard
import java.util.UUID

/**
 * Persistence port for the single active Master Key ceremony and its sealed shards / egress log.
 */
interface CeremonyRepository {
    fun save(ceremony: Ceremony): Ceremony
    fun findById(id: UUID): Ceremony?
    fun findLatest(): Ceremony?
    fun saveShard(shard: SealedShard): SealedShard
    fun findShards(ceremonyId: UUID): List<SealedShard>
    fun replaceShards(ceremonyId: UUID, shardList: List<SealedShard>)
    fun appendEgress(entry: EgressLogEntry): EgressLogEntry
    fun findEgressLog(ceremonyId: UUID): List<EgressLogEntry>
    fun deleteAll()
}
