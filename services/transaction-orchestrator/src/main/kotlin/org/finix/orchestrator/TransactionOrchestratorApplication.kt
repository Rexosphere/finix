package org.finix.orchestrator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TransactionOrchestratorApplication

fun main(args: Array<String>) {
    runApplication<TransactionOrchestratorApplication>(*args)
}
