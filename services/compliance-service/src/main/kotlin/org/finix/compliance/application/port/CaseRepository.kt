package org.finix.compliance.application.port

import org.finix.compliance.domain.Case
import java.util.UUID

/** Persistence port for the [Case] aggregate. */
interface CaseRepository {
    fun save(case: Case): Case
    fun findById(id: UUID): Case?
    fun findAll(): List<Case>
}
