package org.finix.vault.adapter.`in`.rest

import org.finix.vault.application.CeremonyStatus
import org.finix.vault.application.ReconstructResult
import org.finix.vault.application.usecase.ApproveCeremonyUseCase
import org.finix.vault.application.usecase.GetCeremonyStatusUseCase
import org.finix.vault.application.usecase.GetEgressLogUseCase
import org.finix.vault.application.usecase.ReconstructMasterKeyUseCase
import org.finix.vault.application.usecase.SeedVaultUseCase
import org.finix.vault.application.usecase.StartCeremonyUseCase
import org.finix.vault.domain.Ceremony
import org.finix.vault.domain.CeremonyState
import org.finix.vault.domain.CustodianId
import org.finix.vault.domain.EgressLogEntry
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/vault")
class VaultController(
    private val startCeremony: StartCeremonyUseCase,
    private val approveCeremony: ApproveCeremonyUseCase,
    private val getCeremonyStatus: GetCeremonyStatusUseCase,
    private val reconstructMasterKey: ReconstructMasterKeyUseCase,
    private val getEgressLog: GetEgressLogUseCase,
    private val seedVault: SeedVaultUseCase,
) {

    @PostMapping("/ceremony/start")
    @ResponseStatus(HttpStatus.CREATED)
    fun start(): CeremonyResponse = CeremonyResponse.from(startCeremony.execute())

    @PostMapping("/ceremony/approve/{custodianId}")
    fun approve(@PathVariable custodianId: String): CeremonyResponse =
        CeremonyResponse.from(approveCeremony.execute(CustodianId.parse(custodianId)))

    @GetMapping("/ceremony")
    fun status(): CeremonyStatusResponse = CeremonyStatusResponse.from(getCeremonyStatus.execute())

    @PostMapping("/ceremony/reconstruct")
    fun reconstruct(): ReconstructResponse = ReconstructResponse.from(reconstructMasterKey.execute())

    @GetMapping("/ceremony/egress-log")
    fun egressLog(): EgressLogResponse =
        EgressLogResponse(entries = getEgressLog.execute().map { EgressLogLine.from(it) })

    @PostMapping("/admin/seed")
    fun seed(@RequestParam(defaultValue = "false") force: Boolean): CeremonyResponse =
        CeremonyResponse.from(seedVault.execute(force = force))
}

data class CeremonyResponse(
    val id: UUID,
    val state: CeremonyState,
    val threshold: Int,
    val approvals: List<String>,
    val approvalCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(ceremony: Ceremony): CeremonyResponse = CeremonyResponse(
            id = ceremony.id,
            state = ceremony.state,
            threshold = ceremony.threshold,
            approvals = ceremony.approvals.map { it.name }.sorted(),
            approvalCount = ceremony.approvalCount,
            createdAt = ceremony.createdAt,
            updatedAt = ceremony.updatedAt,
        )
    }
}

data class CeremonyStatusResponse(
    val id: UUID,
    val state: CeremonyState,
    val threshold: Int,
    val approvals: List<String>,
    val approvalCount: Int,
    val shardCount: Int,
    val custodians: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(status: CeremonyStatus): CeremonyStatusResponse = CeremonyStatusResponse(
            id = status.ceremony.id,
            state = status.ceremony.state,
            threshold = status.ceremony.threshold,
            approvals = status.ceremony.approvals.map { it.name }.sorted(),
            approvalCount = status.ceremony.approvalCount,
            shardCount = status.shardCount,
            custodians = status.custodians.map { it.name },
            createdAt = status.ceremony.createdAt,
            updatedAt = status.ceremony.updatedAt,
        )
    }
}

data class ReconstructResponse(
    val networkConfigPlaintext: String,
    val egressLog: List<String>,
    val note: String = "Master Key never left the enclave; only network-config plaintext is returned",
) {
    companion object {
        fun from(result: ReconstructResult): ReconstructResponse = ReconstructResponse(
            networkConfigPlaintext = result.networkConfigPlaintext,
            egressLog = result.egressLog,
        )
    }
}

data class EgressLogResponse(val entries: List<EgressLogLine>)

data class EgressLogLine(
    val id: UUID,
    val ceremonyId: UUID,
    val recordedAt: Instant,
    val message: String,
) {
    companion object {
        fun from(entry: EgressLogEntry): EgressLogLine = EgressLogLine(
            id = entry.id,
            ceremonyId = entry.ceremonyId,
            recordedAt = entry.recordedAt,
            message = entry.message,
        )
    }
}
