package org.finix.ledger.adapter.`in`.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.finix.kernel.domain.Money
import org.finix.ledger.application.usecase.AnchorWindowUseCase
import org.finix.ledger.application.usecase.GetJournalUseCase
import org.finix.ledger.application.usecase.GetProofUseCase
import org.finix.ledger.application.usecase.InjectTamperUseCase
import org.finix.ledger.application.usecase.ListAnchorsUseCase
import org.finix.ledger.application.usecase.PostJournalUseCase
import org.finix.ledger.application.usecase.VerifyLedgerUseCase
import org.finix.ledger.domain.EntrySide
import org.finix.ledger.domain.JournalEntry
import org.finix.ledger.domain.JournalLine
import org.finix.ledger.domain.LedgerAnchor
import org.finix.ledger.domain.LedgerProof
import org.finix.ledger.domain.MerkleProofStep
import org.finix.ledger.domain.VerificationReport
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.Base64
import java.util.UUID

@RestController
@RequestMapping("/api/v1/ledger")
class LedgerController(
    private val postJournalUseCase: PostJournalUseCase,
    private val getJournalUseCase: GetJournalUseCase,
    private val verifyLedgerUseCase: VerifyLedgerUseCase,
    private val getProofUseCase: GetProofUseCase,
    private val listAnchorsUseCase: ListAnchorsUseCase,
    private val anchorWindowUseCase: AnchorWindowUseCase,
) {

    @PostMapping("/journals")
    @ResponseStatus(HttpStatus.CREATED)
    fun postJournal(@Valid @RequestBody request: PostJournalRequest): JournalEntryResponse {
        val lines = request.lines.map { it.toDomain() }
        return JournalEntryResponse.from(postJournalUseCase(request.transactionId, lines))
    }

    @GetMapping("/journals/{transactionId}")
    fun getJournal(@PathVariable transactionId: UUID): JournalEntryResponse =
        JournalEntryResponse.from(getJournalUseCase(transactionId))

    @GetMapping("/verify")
    fun verify(): VerificationResponse = VerificationResponse.from(verifyLedgerUseCase())

    @GetMapping("/proof/{transactionId}")
    fun proof(@PathVariable transactionId: UUID): ProofResponse =
        ProofResponse.from(getProofUseCase(transactionId))

    @GetMapping("/anchors")
    fun anchors(): List<AnchorResponse> =
        listAnchorsUseCase.execute().map { AnchorResponse.from(it) }

    @PostMapping("/anchors/now")
    fun anchorNow(): AnchorResponse? =
        anchorWindowUseCase.execute()?.let { AnchorResponse.from(it) }
}

@RestController
@RequestMapping("/api/v1/ledger")
@Profile("dev", "default")
class LedgerTamperController(
    private val injectTamper: InjectTamperUseCase,
) {
    /** Dev-only: flip one hex digit so `/verify` pinpoints the broken link. */
    @PostMapping("/admin/tamper/{sequence}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun tamper(@PathVariable sequence: Long) {
        injectTamper.execute(sequence)
    }
}

data class PostJournalRequest(
    @field:NotNull
    val transactionId: UUID,
    @field:NotEmpty
    val lines: List<JournalLineRequest>,
)

data class JournalLineRequest(
    @field:NotNull
    val accountId: UUID,
    @field:NotNull
    val side: EntrySide,
    @field:NotBlank
    val amount: String,
) {
    fun toDomain(): JournalLine = JournalLine(
        accountId = accountId,
        side = side,
        amount = Money.parse(amount),
    )
}

data class JournalEntryResponse(
    val id: UUID,
    val transactionId: UUID,
    val sequence: Long,
    val prevHash: String,
    val entryHash: String,
    val recordedAt: Instant,
    val lines: List<JournalLineResponse>,
) {
    companion object {
        fun from(entry: JournalEntry): JournalEntryResponse = JournalEntryResponse(
            id = entry.id,
            transactionId = entry.transactionId,
            sequence = entry.sequence,
            prevHash = entry.prevHash,
            entryHash = entry.entryHash,
            recordedAt = entry.recordedAt,
            lines = entry.lines.map { JournalLineResponse.from(it) },
        )
    }
}

data class JournalLineResponse(
    val accountId: UUID,
    val side: EntrySide,
    val amount: String,
) {
    companion object {
        fun from(line: JournalLine): JournalLineResponse = JournalLineResponse(
            accountId = line.accountId,
            side = line.side,
            amount = line.amount.toString(),
        )
    }
}

data class VerificationResponse(
    val valid: Boolean,
    val checkedEntries: Int,
    val firstBreakSequence: Long? = null,
    val detail: String? = null,
) {
    companion object {
        fun from(report: VerificationReport): VerificationResponse = VerificationResponse(
            valid = report.valid,
            checkedEntries = report.checkedEntries,
            firstBreakSequence = report.firstBreakSequence,
            detail = report.detail,
        )
    }
}

data class ProofResponse(
    val transactionId: UUID,
    val sequence: Long,
    val prevHash: String,
    val entryHash: String,
    val merkleRoot: String?,
    val merklePath: List<MerkleStepResponse>,
    val leafIndex: Int?,
    val treeSize: Int?,
    val anchorId: UUID?,
    val anchorSignatureBase64: String?,
    val anchorPublicKeyBase64: String?,
    val inclusion: String,
) {
    companion object {
        fun from(proof: LedgerProof): ProofResponse = ProofResponse(
            transactionId = proof.transactionId,
            sequence = proof.sequence,
            prevHash = proof.prevHash,
            entryHash = proof.entryHash,
            merkleRoot = proof.merkleRoot,
            merklePath = proof.merklePath.map { MerkleStepResponse.from(it) },
            leafIndex = proof.leafIndex,
            treeSize = proof.treeSize,
            anchorId = proof.anchorId,
            anchorSignatureBase64 = proof.anchorSignatureBase64,
            anchorPublicKeyBase64 = proof.anchorPublicKeyBase64,
            inclusion = proof.inclusion,
        )
    }
}

data class MerkleStepResponse(
    val siblingHash: String,
    val isLeftSibling: Boolean,
) {
    companion object {
        fun from(step: MerkleProofStep) = MerkleStepResponse(step.siblingHash, step.isLeftSibling)
    }
}

data class AnchorResponse(
    val id: UUID,
    val windowStartSeq: Long,
    val windowEndSeq: Long,
    val merkleRoot: String,
    val entryCount: Int,
    val signatureBase64: String,
    val publicKeyBase64: String,
    val anchoredAt: Instant,
) {
    companion object {
        fun from(anchor: LedgerAnchor) = AnchorResponse(
            id = anchor.id,
            windowStartSeq = anchor.windowStartSeq,
            windowEndSeq = anchor.windowEndSeq,
            merkleRoot = anchor.merkleRoot,
            entryCount = anchor.entryCount,
            signatureBase64 = Base64.getEncoder().encodeToString(anchor.signature),
            publicKeyBase64 = Base64.getEncoder().encodeToString(anchor.publicKey),
            anchoredAt = anchor.anchoredAt,
        )
    }
}
