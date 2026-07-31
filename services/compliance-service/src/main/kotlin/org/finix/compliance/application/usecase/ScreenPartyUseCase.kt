package org.finix.compliance.application.usecase

import org.finix.compliance.application.ScreenPartyCommand
import org.finix.compliance.application.ScreenPartyResult
import org.finix.compliance.application.port.CaseRepository
import org.finix.compliance.domain.Case
import org.finix.compliance.domain.CaseSeverity
import org.finix.compliance.domain.CaseType
import org.finix.compliance.domain.SanctionsScreening
import org.finix.kernel.domain.DomainError
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ScreenPartyUseCase(
    private val cases: CaseRepository,
    private val clock: Clock,
) {
    @Transactional
    fun execute(command: ScreenPartyCommand): ScreenPartyResult {
        domainRequireName(command.name)
        val hit = SanctionsScreening.screen(command.name, command.nic)
        if (!hit.hit) {
            return ScreenPartyResult(hit = false, reasons = emptyList())
        }
        val subject = command.subjectRef?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(command.name.trim(), command.nic?.trim()).joinToString("|")
        val opened = Case.open(
            type = CaseType.SANCTIONS,
            subjectRef = subject,
            severity = CaseSeverity.HIGH,
            notes = "Sanctions screen hit: ${hit.reasons.joinToString()}",
            clock = clock,
        )
        val saved = cases.save(opened)
        return ScreenPartyResult(hit = true, reasons = hit.reasons, caseId = saved.id)
    }

    private fun domainRequireName(name: String) {
        if (name.isBlank()) {
            DomainError.Invalid("name must not be blank").raise()
        }
    }
}
