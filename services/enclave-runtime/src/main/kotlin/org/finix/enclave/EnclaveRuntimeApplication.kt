package org.finix.enclave

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EnclaveRuntimeApplication

fun main(args: Array<String>) {
    runApplication<EnclaveRuntimeApplication>(*args)
}
