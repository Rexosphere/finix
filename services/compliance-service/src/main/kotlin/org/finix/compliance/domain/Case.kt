package org.finix.compliance.domain

import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.domainRequire
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Compliance investigation case (AML / sanctions / fraud / SAR / Travel Rule).
 */
class Case(
    val id: UUID,
    val type: CaseType,
    val subjectRef: String,
    status: CaseStatus,
    severity: CaseSeverity,
    notes: String,
    val openedAt: Instant,
    updatedAt: Instant,
    closedAt: Instant? = null,
    version: Long = 0,
) {
    var status: CaseStatus = status
        private set

    var severity: CaseSeverity = severity
        private set

    var notes: String = notes
        private set

    var updatedAt: Instant = updatedAt
        private set

    var closedAt: Instant? = closedAt
        private set

    var version: Long = version
        private set

    fun updateStatus(
        next: CaseStatus,
        notes: String? = null,
        clock: Clock = Clock.systemUTC(),
    ) {
        when (status) {
            CaseStatus.CLOSED -> domainRequire(next == CaseStatus.CLOSED) {
                DomainError.Conflict(
                    detail = "Case '$id' is CLOSED and cannot move to $next",
                    properties = mapOf("caseId" to id.toString(), "status" to status.name),
                )
            }
            CaseStatus.OPEN, CaseStatus.INVESTIGATING -> Unit
        }
        if (status == next && (notes == null || notes == this.notes)) {
            return
        }
        status = next
        if (notes != null) {
            this.notes = notes
        }
        val now = Instant.now(clock)
        updatedAt = now
        closedAt = if (next == CaseStatus.CLOSED) now else null
    }

    companion object {
        fun open(
            id: UUID = UUID.randomUUID(),
            type: CaseType,
            subjectRef: String,
            severity: CaseSeverity,
            notes: String = "",
            clock: Clock = Clock.systemUTC(),
        ): Case {
            domainRequire(subjectRef.isNotBlank()) {
                DomainError.Invalid("subjectRef must not be blank")
            }
            val now = Instant.now(clock)
            return Case(
                id = id,
                type = type,
                subjectRef = subjectRef.trim(),
                status = CaseStatus.OPEN,
                severity = severity,
                notes = notes,
                openedAt = now,
                updatedAt = now,
            )
        }

        const val RISK_SCORE_MIN: Int = 0
        const val RISK_SCORE_MAX: Int = 100
        private const val CRITICAL_THRESHOLD: Int = 90
        private const val HIGH_THRESHOLD: Int = 75
        private const val MEDIUM_THRESHOLD: Int = 50

        /** Maps a risk-ai score (0..100) onto case severity for from-risk ingestion. */
        fun severityFromRiskScore(score: Int): CaseSeverity = when {
            score >= CRITICAL_THRESHOLD -> CaseSeverity.CRITICAL
            score >= HIGH_THRESHOLD -> CaseSeverity.HIGH
            score >= MEDIUM_THRESHOLD -> CaseSeverity.MEDIUM
            else -> CaseSeverity.LOW
        }
    }
}
