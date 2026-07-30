package org.finix.kernel.test

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture

/**
 * The hexagonal boundary rules, shipped once and asserted by every service.
 *
 * These exist because architecture documents do not enforce themselves. A diagram claiming
 * "the domain has no framework dependencies" is worth nothing the first time someone adds
 * `@Autowired` to a domain class for convenience; a failing build is worth quite a lot.
 *
 * The layering FINIX enforces:
 *
 * ```
 *   adapter/in/{rest,kafka,grpc}  ─┐
 *                                  ├──> application (use cases, ports) ──> domain (pure)
 *   adapter/out/{persistence,...} ─┘
 * ```
 *
 * Two properties follow, and both are load-bearing for this system specifically:
 *
 *  - The **domain is testable without Spring**, so ledger and Shamir invariants can be checked
 *    as fast property-based tests over thousands of generated cases rather than as slow
 *    context-loading integration tests.
 *  - **Adapters are replaceable.** The blueprint's local ledger anchor and its Hyperledger
 *    Fabric anchor are two implementations of one port; that is only true if nothing in the
 *    application layer ever imports the adapter package.
 */
object HexagonalArchitecture {

    /** Applies the rules to `org.finix.<service>` — pass the service's root package. */
    fun rulesFor(basePackage: String): List<ArchRule> = listOf(
        layering(basePackage),
        domainIsFrameworkFree(basePackage),
        domainDoesNotDependOnPersistence(basePackage),
        applicationDoesNotDependOnAdapters(basePackage),
        noFieldInjection(),
        useCasesEndInUseCase(basePackage),
        portsAreInterfaces(basePackage),
        noJavaUtilLogging(),
    )

    fun assertAll(classes: JavaClasses, basePackage: String) {
        rulesFor(basePackage).forEach { it.check(classes) }
    }

    private fun layering(basePackage: String): ArchRule =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Domain").definedBy("$basePackage.domain..")
            .layer("Application").definedBy("$basePackage.application..")
            .layer("Adapters").definedBy("$basePackage.adapter..")
            .layer("Configuration").definedBy("$basePackage.config..")
            .whereLayer("Adapters").mayOnlyBeAccessedByLayers("Configuration")
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Configuration")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters", "Configuration")
            .`as`("the hexagon points inward: adapters depend on the application, never the reverse")

    /**
     * The strictest rule, and the one that pays for itself. Money, ledger entries and Shamir
     * shards must not know that Spring, JPA or Jackson exist.
     */
    private fun domainIsFrameworkFree(basePackage: String): ArchRule =
        noClasses().that().resideInAPackage("$basePackage.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "jakarta.servlet..",
                "com.fasterxml.jackson..",
                "io.swagger..",
                "org.apache.kafka..",
            )
            .`as`("domain classes must stay pure so they can be reasoned about and property-tested in isolation")

    private fun domainDoesNotDependOnPersistence(basePackage: String): ArchRule =
        noClasses().that().resideInAPackage("$basePackage.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
            .`as`("a domain model that knows about SQL has become a database schema with methods")

    private fun applicationDoesNotDependOnAdapters(basePackage: String): ArchRule =
        noClasses().that().resideInAPackage("$basePackage.application..")
            .should().dependOnClassesThat().resideInAPackage("$basePackage.adapter..")
            .`as`("use cases depend on ports, so an adapter can be swapped without touching them")

    /**
     * Constructor injection only. Field injection hides a dependency from the constructor, which
     * is exactly where a reviewer looks to see what a class actually needs — and it makes the
     * class untestable without a Spring context.
     */
    private fun noFieldInjection(): ArchRule =
        noClasses().should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .orShould().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.beans.factory.annotation.Autowired")
            .`as`("dependencies are declared in the constructor, where they are visible and testable")

    /** Naming is navigation: `TransferMoneyUseCase` is findable, `TransferService` is not. */
    private fun useCasesEndInUseCase(basePackage: String): ArchRule =
        classes().that().resideInAPackage("$basePackage.application.usecase..")
            .and().areTopLevelClasses()
            .and().areNotInterfaces()
            .should().haveSimpleNameEndingWith("UseCase")
            .`as`("application use cases are named for what they do")

    private fun portsAreInterfaces(basePackage: String): ArchRule =
        classes().that().resideInAPackage("$basePackage.application.port..")
            .and().areTopLevelClasses()
            .should().beInterfaces()
            .orShould().beRecords()
            .`as`("a port is a contract; a concrete class in the port package is an adapter in disguise")

    private fun noJavaUtilLogging(): ArchRule =
        noClasses().should().dependOnClassesThat().resideInAPackage("java.util.logging..")
            .`as`("logs are structured JSON via SLF4J so they are queryable in Loki")
}
