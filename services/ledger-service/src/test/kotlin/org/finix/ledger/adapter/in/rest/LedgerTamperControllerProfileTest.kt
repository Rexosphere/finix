package org.finix.ledger.adapter.`in`.rest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.finix.ledger.application.usecase.InjectTamperUseCase
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.function.Supplier

/**
 * Production-safety guard for the deliberate ledger-corruption endpoint.
 *
 * [LedgerTamperController] exposes `POST /api/v1/ledger/admin/tamper/{sequence}`, which rewrites a
 * committed journal entry's hash through a privileged SQL function. It exists only so the
 * immutability demo can show `/verify` pinpointing the break, so it must be reachable only when a
 * developer has *explicitly asked* for a development profile.
 *
 * The failure mode this test exists to prevent: Spring activates the reserved `default` profile
 * whenever no profile has been selected, so spelling the annotation `@Profile("dev", "default")`
 * silently matches an ordinary production boot. No FINIX deployment sets `SPRING_PROFILES_ACTIVE`,
 * which would put a ledger-corruption endpoint in the deployed banking system.
 *
 * The invariant, stated once: **the absence of a profile must never enable this controller.**
 */
class LedgerTamperControllerProfileTest : StringSpec({

    /**
     * Registers the controller in a bare Spring context under [activeProfiles] and reports whether
     * the bean survived condition evaluation.
     *
     * Registering the class directly still honours `@Profile` — it is implemented as
     * `@Conditional(ProfileCondition)` and evaluated by `AnnotatedBeanDefinitionReader` — so this
     * measures real container behaviour rather than re-reading the annotation's own value.
     */
    fun tamperControllerIsRegistered(vararg activeProfiles: String): Boolean =
        AnnotationConfigApplicationContext().use { context ->
            // No argument clears the active profiles, which is precisely how a production JVM
            // starts when SPRING_PROFILES_ACTIVE is unset.
            context.environment.setActiveProfiles(*activeProfiles)
            context.registerBean(
                InjectTamperUseCase::class.java,
                Supplier { mockk<InjectTamperUseCase>(relaxed = true) },
            )
            context.register(LedgerTamperController::class.java)
            context.refresh()
            context.getBeanNamesForType(LedgerTamperController::class.java).isNotEmpty()
        }

    "tamper endpoint is absent when no profile is selected - the production boot" {
        tamperControllerIsRegistered() shouldBe false
    }

    "tamper endpoint is absent under a production-style profile" {
        tamperControllerIsRegistered("prod") shouldBe false
    }

    "tamper endpoint is absent under the integration-test profile" {
        tamperControllerIsRegistered("integration-test") shouldBe false
    }

    "tamper endpoint is present when dev is explicitly selected - the demo keeps working" {
        tamperControllerIsRegistered("dev") shouldBe true
    }

    "tamper endpoint is present when dev is selected alongside other profiles" {
        tamperControllerIsRegistered("dev", "local") shouldBe true
    }
})
