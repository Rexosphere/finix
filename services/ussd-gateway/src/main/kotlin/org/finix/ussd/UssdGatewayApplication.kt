package org.finix.ussd

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class UssdGatewayApplication

fun main(args: Array<String>) {
    runApplication<UssdGatewayApplication>(*args)
}
