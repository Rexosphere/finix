package org.finix.compliance.application.usecase

import org.finix.compliance.application.port.CaseRepository
import org.finix.compliance.domain.Case
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ListCasesUseCase(
    private val cases: CaseRepository,
) {
    @Transactional(readOnly = true)
    fun execute(): List<Case> = cases.findAll()
}
