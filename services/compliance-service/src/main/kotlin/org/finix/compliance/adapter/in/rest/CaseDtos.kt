package org.finix.compliance.adapter.`in`.rest

import org.finix.compliance.domain.Case
import org.finix.compliance.domain.CaseSeverity
import org.finix.compliance.domain.CaseStatus
import org.finix.compliance.domain.CaseType
import java.util.UUID

data class OpenCaseRequest(
    val type: CaseType,
    val subjectRef: String,
    val severity: CaseSeverity,
    val notes: String = "",
)

data class UpdateCaseRequest(
    val status: CaseStatus,
    val notes: String? = null,
)

data class ScreenPartyRequest(
    val name: String,
    val nic: String? = null,
    val subjectRef: String? = null,
)

data class FromRiskCaseRequest(
    val transactionId: String,
    val score: Int,
    val reasons: List<String> = emptyList(),
)

data class CaseResponse(
    val id: UUID,
    val type: CaseType,
    val subjectRef: String,
    val status: CaseStatus,
    val severity: CaseSeverity,
    val notes: String,
    val openedAt: String,
    val updatedAt: String,
    val closedAt: String?,
)

data class ScreenPartyResponse(
    val hit: Boolean,
    val reasons: List<String>,
    val caseId: UUID?,
)

fun Case.toResponse(): CaseResponse = CaseResponse(
    id = id,
    type = type,
    subjectRef = subjectRef,
    status = status,
    severity = severity,
    notes = notes,
    openedAt = openedAt.toString(),
    updatedAt = updatedAt.toString(),
    closedAt = closedAt?.toString(),
)
