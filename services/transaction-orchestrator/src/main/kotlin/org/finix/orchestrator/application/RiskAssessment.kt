package org.finix.orchestrator.application

data class RiskAssessment(
    val score: Int,
    val decision: String,
    val reasons: List<String> = emptyList(),
    val caseId: String? = null,
)
