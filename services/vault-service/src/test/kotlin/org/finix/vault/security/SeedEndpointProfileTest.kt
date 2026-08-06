package org.finix.vault.security

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner
import org.springframework.context.support.GenericApplicationContext
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

private const val REST_PACKAGE = "org.finix.vault.adapter.in.rest"
private const val SEED_ROUTE = "POST /api/v1/vault/admin/seed"
private const val CEREMONY_STATUS_ROUTE = "GET /api/v1/vault/ceremony"
private const val CEREMONY_START_ROUTE = "POST /api/v1/vault/ceremony/start"
private const val CEREMONY_APPROVE_ROUTE = "POST /api/v1/vault/ceremony/approve/{custodianId}"
private const val CEREMONY_RECONSTRUCT_ROUTE = "POST /api/v1/vault/ceremony/reconstruct"

/**
 * Demo-data seeding must not exist on an ordinary boot — and here that is the sharpest case in
 * the system.
 *
 * `SeedVaultUseCase` calls `ceremonies.deleteAll()` and re-splits a fresh master key. Reaching
 * `POST /api/v1/vault/admin/seed?force=true` therefore destroys every existing custodian shard
 * and replaces the key they protect. On a default boot that endpoint is registered and, because
 * `permit-all` is on, reachable without a token.
 *
 * Gating it behind an explicitly selected profile makes a production boot incapable of serving
 * it. The ceremony API itself is genuine operator administration and must keep working.
 */
class SeedEndpointProfileTest : StringSpec({

    "seed endpoint is absent when no Spring profile is selected" {
        mappedRoutes() shouldNotContain SEED_ROUTE
    }

    "seed endpoint is absent under the prod profile" {
        mappedRoutes("prod") shouldNotContain SEED_ROUTE
    }

    "seed endpoint is absent under the test profile" {
        mappedRoutes("test") shouldNotContain SEED_ROUTE
    }

    "seed endpoint is present under an explicitly selected dev profile" {
        mappedRoutes("dev") shouldContain SEED_ROUTE
    }

    // The key ceremony is legitimate production administration, not demo seeding. If gating the
    // seed endpoint were to remove these, the fix would have broken the vault.
    "ceremony administration survives on a default boot" {
        val routes = mappedRoutes()
        routes shouldContain CEREMONY_STATUS_ROUTE
        routes shouldContain CEREMONY_START_ROUTE
        routes shouldContain CEREMONY_APPROVE_ROUTE
        routes shouldContain CEREMONY_RECONSTRUCT_ROUTE
    }

    "ceremony administration survives under the prod profile" {
        val routes = mappedRoutes("prod")
        routes shouldContain CEREMONY_STATUS_ROUTE
        routes shouldContain CEREMONY_START_ROUTE
        routes shouldContain CEREMONY_APPROVE_ROUTE
        routes shouldContain CEREMONY_RECONSTRUCT_ROUTE
    }
})

/** Routes a real component scan of the REST adapter package would register for [profiles]. */
private fun mappedRoutes(vararg profiles: String): Set<String> =
    GenericApplicationContext().use { context ->
        context.environment.setActiveProfiles(*profiles)
        ClassPathBeanDefinitionScanner(context).scan(REST_PACKAGE)
        context.beanDefinitionNames
            .mapNotNull { context.getBeanDefinition(it).beanClassName }
            .map { Class.forName(it) }
            .flatMap(::routesOf)
            .toSet()
    }

private fun routesOf(type: Class<*>): List<String> {
    val base = type.getAnnotation(RequestMapping::class.java)?.value?.firstOrNull().orEmpty()
    return type.declaredMethods.flatMap { method ->
        routes("GET", base, method.getAnnotation(GetMapping::class.java)?.value) +
            routes("POST", base, method.getAnnotation(PostMapping::class.java)?.value)
    }
}

private fun routes(verb: String, base: String, paths: Array<String>?): List<String> {
    if (paths == null) return emptyList()
    val values = if (paths.isEmpty()) arrayOf("") else paths
    return values.map { "$verb$SPACE$base$it" }
}

private const val SPACE = " "
