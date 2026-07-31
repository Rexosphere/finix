package org.finix.compliance.application

import org.finix.compliance.domain.CaseSeverity
import org.finix.compliance.domain.CaseStatus
import org.finix.compliance.domain.CaseType
import java.util.UUID

data class OpenCaseCommand(
    val type: CaseType,
    val subjectRef: String,
    val severity: CaseSeverity,
    val notes: String = "",
)

data class UpdateCaseStatusCommand(
    val caseId: UUID,
    val status: CaseStatus,
    val notes: String? = null,
)

data class ScreenPartyCommand(
    val name: String,
    val nic: String? = null,
    val subjectRef: String? = null,
)

data class ScreenPartyResult(
    val hit: Boolean,
    val reasons: List<String>,
    val caseId: UUID? = null,
)
