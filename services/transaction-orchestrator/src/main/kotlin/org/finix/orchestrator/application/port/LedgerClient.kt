package org.finix.orchestrator.application.port

import org.finix.orchestrator.application.JournalLineCommand
import java.util.UUID

interface LedgerClient {
    fun postJournal(transactionId: UUID, lines: List<JournalLineCommand>)
}
