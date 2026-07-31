package org.finix.compliance.adapter.`in`.rest

import jakarta.validation.Valid
import org.finix.compliance.application.OpenCaseCommand
import org.finix.compliance.application.ScreenPartyCommand
import org.finix.compliance.application.UpdateCaseStatusCommand
import org.finix.compliance.application.usecase.ListCasesUseCase
import org.finix.compliance.application.usecase.OpenCaseUseCase
import org.finix.compliance.application.usecase.ScreenPartyUseCase
import org.finix.compliance.application.usecase.UpdateCaseStatusUseCase
import org.finix.compliance.domain.Case
import org.finix.compliance.domain.CaseType
import org.finix.kernel.domain.DomainError
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class CaseController(
    private val openCase: OpenCaseUseCase,
    private val listCases: ListCasesUseCase,
    private val updateCaseStatus: UpdateCaseStatusUseCase,
    private val screenParty: ScreenPartyUseCase,
) {

    @PostMapping("/cases")
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@Valid @RequestBody body: OpenCaseRequest): CaseResponse =
        openCase.execute(
            OpenCaseCommand(
                type = body.type,
                subjectRef = body.subjectRef,
                severity = body.severity,
                notes = body.notes,
            ),
        ).toResponse()

    @GetMapping("/cases")
    fun list(): List<CaseResponse> =
        listCases.execute().map { it.toResponse() }

    @PatchMapping("/cases/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody body: UpdateCaseRequest,
    ): CaseResponse =
        updateCaseStatus.execute(
            UpdateCaseStatusCommand(
                caseId = id,
                status = body.status,
                notes = body.notes,
            ),
        ).toResponse()

    @PostMapping("/cases/from-risk")
    @ResponseStatus(HttpStatus.CREATED)
    fun fromRisk(@Valid @RequestBody body: FromRiskCaseRequest): CaseResponse {
        if (body.transactionId.isBlank()) {
            DomainError.Invalid("transactionId must not be blank").raise()
        }
        if (body.score !in Case.RISK_SCORE_MIN..Case.RISK_SCORE_MAX) {
            DomainError.Invalid(
                detail = "score must be ${Case.RISK_SCORE_MIN}..${Case.RISK_SCORE_MAX}, got ${body.score}",
                properties = mapOf("score" to body.score),
            ).raise()
        }
        val notes = buildString {
            append("risk-ai score=${body.score}")
            if (body.reasons.isNotEmpty()) {
                append("; reasons=").append(body.reasons.joinToString())
            }
        }
        return openCase.execute(
            OpenCaseCommand(
                type = CaseType.FRAUD,
                subjectRef = body.transactionId.trim(),
                severity = Case.severityFromRiskScore(body.score),
                notes = notes,
            ),
        ).toResponse()
    }

    @PostMapping("/screen")
    fun screen(@Valid @RequestBody body: ScreenPartyRequest): ScreenPartyResponse {
        val result = screenParty.execute(
            ScreenPartyCommand(
                name = body.name,
                nic = body.nic,
                subjectRef = body.subjectRef,
            ),
        )
        return ScreenPartyResponse(
            hit = result.hit,
            reasons = result.reasons,
            caseId = result.caseId,
        )
    }
}
