package org.finix.identity.security

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner
import org.springframework.context.support.GenericApplicationContext
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

private const val REST_PACKAGE = "org.finix.identity.adapter.in.rest"
private const val SEED_ROUTE = "POST /api/v1/admin/seed"
private const val PROFILE_ROUTE = "GET /api/v1/me"
private const val DEVICE_ROUTE = "POST /api/v1/me/devices"
private const val TOKEN_ROUTE = "POST /api/v1/auth/token"

/**
 * Demo-data seeding must not exist on an ordinary boot.
 *
 * `POST /api/v1/admin/seed` writes the five Keycloak demo personas into the identity database.
 * The shared security configuration lists that exact path as `permitAll()`, so on a default boot
 * it is an unauthenticated write endpoint. Gating it behind an explicitly selected profile means
 * a production boot cannot expose it at all, which is a stronger guarantee than authorising it.
 *
 * Assertions are on routes rather than class names so they survive the controller being split,
 * and so they cannot be satisfied by relocating the mapping.
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

    // The identity service also carries the login BFF and the customer profile API. Gating the
    // seed endpoint must leave both untouched.
    "production identity routes survive on a default boot" {
        val routes = mappedRoutes()
        routes shouldContain PROFILE_ROUTE
        routes shouldContain DEVICE_ROUTE
        routes shouldContain TOKEN_ROUTE
    }

    "production identity routes survive under the prod profile" {
        val routes = mappedRoutes("prod")
        routes shouldContain PROFILE_ROUTE
        routes shouldContain DEVICE_ROUTE
        routes shouldContain TOKEN_ROUTE
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
