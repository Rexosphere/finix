package org.finix.account.security

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner
import org.springframework.context.support.GenericApplicationContext
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

private const val REST_PACKAGE = "org.finix.account.adapter.in.rest"
private const val SEED_ROUTE = "POST /api/v1/admin/seed"
private const val BALANCE_ROUTE = "GET /api/v1/accounts/{id}"
private const val RESERVE_ROUTE = "POST /api/v1/accounts/{id}/reserves"

/**
 * Demo-data seeding must not exist on an ordinary boot.
 *
 * `POST /api/v1/admin/seed` creates the three blueprint persona accounts with fixed ids and
 * opening balances. That is a development convenience, and the shared security configuration
 * additionally lists it as `permitAll()` — so on a default boot it is an unauthenticated write
 * endpoint. The safe property is therefore not "it is protected" but "it is not there at all":
 * absence of an explicitly selected profile must mean the bean is never registered.
 *
 * The assertions below deliberately talk about *routes*, not class names, so they keep their
 * meaning however the controller is later split up — and so they cannot be satisfied by moving
 * the method somewhere else that is still mapped.
 *
 * Component scanning is reproduced rather than mocked: [ClassPathBeanDefinitionScanner]
 * evaluates `@Profile` against the context environment exactly as Spring Boot's `@ComponentScan`
 * does, and does so without refreshing the context, so no database or mock wiring is involved.
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

    // Guards the other half of the contract: gating the demo endpoint must not take the
    // production account API with it. Without this, annotating the whole controller would
    // look like a passing fix.
    "production account routes survive on a default boot" {
        val routes = mappedRoutes()
        routes shouldContain BALANCE_ROUTE
        routes shouldContain RESERVE_ROUTE
    }

    "production account routes survive under the prod profile" {
        val routes = mappedRoutes("prod")
        routes shouldContain BALANCE_ROUTE
        routes shouldContain RESERVE_ROUTE
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
