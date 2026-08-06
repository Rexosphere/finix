package org.finix.vault.adapter.`in`.rest

import org.finix.vault.application.usecase.SeedVaultUseCase
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Demo vault seeding, and nothing else — the most destructive endpoint in the service, so it
 * gets the strongest guarantee available: on an ordinary boot it does not exist.
 *
 * [SeedVaultUseCase] deletes every ceremony row and re-splits a fresh master key. Calling this
 * with `force=true` therefore destroys the shards the custodians hold and replaces the key they
 * protect. That is exactly what a demo reset needs and exactly what production must never be
 * able to serve — least of all unauthenticated, which is what `permit-all` makes it today.
 *
 * `@Profile("dev")` — not `("dev", "default")` — means a service booted with no profile
 * selected never registers the bean. The ceremony API in [VaultController] is genuine operator
 * administration and is deliberately left alone.
 */
@RestController
@RequestMapping("/api/v1/vault")
@Profile("dev")
class VaultSeedController(
    private val seedVault: SeedVaultUseCase,
) {

    @PostMapping("/admin/seed")
    fun seed(@RequestParam(defaultValue = "false") force: Boolean): CeremonyResponse =
        CeremonyResponse.from(seedVault.execute(force = force))
}
