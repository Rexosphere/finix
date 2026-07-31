package org.finix.compliance.application.usecase

import org.finix.compliance.application.OpenCaseCommand
import org.finix.compliance.application.port.CaseRepository
import org.finix.compliance.domain.Case
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class OpenCaseUseCase(
    private val cases: CaseRepository,
    private val clock: Clock,
) {
    @Transactional
    fun execute(command: OpenCaseCommand): Case {
        val opened = Case.open(
            type = command.type,
            subjectRef = command.subjectRef,
            severity = command.severity,
            notes = command.notes,
            clock = clock,
        )
        return cases.save(opened)
    }
}
