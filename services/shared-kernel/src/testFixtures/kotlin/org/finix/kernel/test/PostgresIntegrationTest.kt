package org.finix.kernel.test

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * A PostgreSQL container shared by every integration test in a module.
 *
 * It is a singleton started once per JVM and never stopped, rather than a `@Testcontainers`
 * per-class container. Starting Postgres costs a couple of seconds; multiplied across a dozen
 * test classes that is a minute of CI per service, per run. Ryuk reaps it when the JVM exits.
 *
 * The image is pinned by digest-friendly tag rather than `latest` for the obvious reason: a test
 * suite whose dependencies move underneath it is a test suite that fails for reasons unrelated
 * to the change being reviewed.
 *
 * Usage:
 * ```
 * @SpringBootTest
 * class LedgerPersistenceIT : PostgresIntegrationTest() { ... }
 * ```
 * Flyway runs against the real database, so migrations are verified on every integration run —
 * a broken migration cannot reach `main` unnoticed.
 */
abstract class PostgresIntegrationTest {

    companion object {
        const val POSTGRES_IMAGE: String = "postgres:16-alpine"

        @JvmStatic
        protected val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("finix_test")
                .withUsername("finix")
                .withPassword("finix")
                // Tests do not need durability, and fsync dominates the runtime of a suite that
                // writes thousands of ledger rows.
                .withCommand("postgres", "-c", "fsync=off", "-c", "full_page_writes=off")
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.locations") { "classpath:db/migration,classpath:db/kernel" }
        }
    }
}
