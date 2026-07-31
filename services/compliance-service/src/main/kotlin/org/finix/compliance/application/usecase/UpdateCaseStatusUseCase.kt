package org.finix.compliance.application.usecase

import org.finix.compliance.application.UpdateCaseStatusCommand
import org.finix.compliance.application.port.CaseRepository
import org.finix.compliance.domain.Case
import org.finix.kernel.domain.DomainError
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class UpdateCaseStatusUseCase(
    private val cases: CaseRepository,
    private val clock: Clock,
) {
    @Transactional
    fun execute(command: UpdateCaseStatusCommand): Case {
        val existing = cases.findById(command.caseId)
            ?: DomainError.NotFound("Case", command.caseId.toString()).raise()
        existing.updateStatus(command.status, command.notes, clock)
        return cases.save(existing)
    }
}
