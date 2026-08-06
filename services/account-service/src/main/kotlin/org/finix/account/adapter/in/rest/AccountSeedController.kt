package org.finix.account.adapter.`in`.rest

import org.finix.account.application.usecase.SeedAccountsUseCase
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Demo-data seeding, and nothing else — deliberately a separate bean so it can be switched off
 * as a whole.
 *
 * This endpoint opens the three blueprint persona accounts with fixed ids and opening balances.
 * It is a development convenience, and the shared security configuration additionally lists
 * `POST /api/v1/admin/seed` as `permitAll()`, so wherever it exists it is an unauthenticated
 * write endpoint.
 *
 * `@Profile("dev")` — not `("dev", "default")` — is the point: a service booted with no profile
 * selected, which is exactly how production runs, never registers this bean, so the route
 * returns 404 rather than relying on an authorisation rule to hold. The seeding logic itself
 * stays in [SeedAccountsUseCase] and remains callable from tests and from a dev boot.
 */
@RestController
@RequestMapping("/api/v1")
@Profile("dev")
class AccountSeedController(
    private val seedAccounts: SeedAccountsUseCase,
) {

    @PostMapping("/admin/seed")
    fun seed(): SeedAccountsResponse =
        SeedAccountsResponse(accounts = seedAccounts.execute().map { it.toResponse() })
}
