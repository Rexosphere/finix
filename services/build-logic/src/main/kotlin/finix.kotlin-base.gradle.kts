import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Baseline contract for every JVM module in FINIX: toolchain, compiler strictness,
 * static analysis and the test harness. Applied transitively by `finix.spring-service`.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.gitlab.arturbosch.detekt")
    jacoco
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "org.finix"
version = providers.gradleProperty("finixVersion").getOrElse("0.1.0-SNAPSHOT")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Warnings are defects: a service that compiles dirty does not merge.
        allWarningsAsErrors.set(true)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        sarif.required.set(true) // uploaded to GitHub code scanning
        xml.required.set(false)
        txt.required.set(false)
        md.required.set(false)
    }
}

// Integration tests run against real Postgres/Kafka/Keycloak via Testcontainers and live in a
// separate source set, so the `test` task stays fast enough to run on every save.
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs Testcontainers-backed integration tests."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
    systemProperty("spring.profiles.active", "integration-test")
}

dependencies {
    "implementation"(libs.findLibrary("kotlin-reflect").get())
    "implementation"(libs.findLibrary("kotlin-logging").get())

    "testImplementation"(libs.findLibrary("kotest-runner-junit5").get())
    "testImplementation"(libs.findLibrary("kotest-assertions-core").get())
    "testImplementation"(libs.findLibrary("kotest-property").get())
    "testImplementation"(libs.findLibrary("mockk").get())
    "testImplementation"(libs.findLibrary("archunit-junit5").get())
    "testImplementation"(libs.findLibrary("awaitility").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Djava.security.egd=file:/dev/./urandom")
    systemProperty("user.timezone", "UTC")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true) // consumed by SonarQube + the CI coverage comment
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    // Generated/framework glue is not meaningful coverage surface.
                    exclude("**/*Application*", "**/config/**", "**/dto/**", "**/generated/**")
                }
            }
        )
    )
}

tasks.named("test") { finalizedBy(tasks.named("jacocoTestReport")) }

/**
 * Coverage gate scoped to the hexagon interior (`domain`, `application`, `crypto`).
 * Adapters are covered by integration tests instead, so a single global % would be misleading.
 */
val domainCoverage = tasks.register<JacocoCoverageVerification>("jacocoDomainCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    executionData(tasks.named<Test>("test").map { it.extensions.getByType<JacocoTaskExtension>().destinationFile!! })
    sourceDirectories.setFrom(files("src/main/kotlin"))
    classDirectories.setFrom(
        files(
            fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
                // The hexagon interior: pure logic with no framework dependencies.
                include("**/domain/**", "**/application/**", "**/crypto/**")
            }
        )
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

// `check` stays runnable without Docker; integrationTest is a separate CI stage.
tasks.named("check") {
    dependsOn(domainCoverage)
}
