package org.finix.vault

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class VaultServiceApplication

fun main(args: Array<String>) {
    runApplication<VaultServiceApplication>(*args)
}
