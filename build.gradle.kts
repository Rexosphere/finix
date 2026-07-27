plugins {
    base
}

/**
 * Aggregate entry points so CI (and humans) have one command per intent.
 * Each guards on task existence: non-JVM services (Go, Python, Node) live in the same
 * repository but are built by their own toolchains.
 */
fun aggregate(name: String, group: String, description: String, target: String) =
    tasks.register(name) {
        this.group = group
        this.description = description
        dependsOn(
            subprojects.mapNotNull { sp ->
                sp.tasks.findByName(target)?.let { "${sp.path}:$target" }
            }
        )
    }


gradle.projectsEvaluated {
    aggregate(
        name = "verify",
        group = "verification",
        description = "Full local gate: compile, unit + architecture tests, coverage, static analysis.",
        target = "check",
    )
    aggregate(
        name = "integrationTest",
        group = "verification",
        description = "Testcontainers-backed integration suites across all services.",
        target = "integrationTest",
    )
}
