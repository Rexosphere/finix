package org.finix.compliance

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ComplianceServiceApplication

fun main(args: Array<String>) {
    runApplication<ComplianceServiceApplication>(*args)
}
