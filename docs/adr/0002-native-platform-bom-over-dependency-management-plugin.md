# 2. Gradle native `platform()` BOM, not `io.spring.dependency-management`

- Status: Accepted
- Date: 2026-07-29

## Context

Spring Boot projects have historically used the `io.spring.dependency-management` Gradle plugin to
apply the Boot BOM. Gradle has supported BOMs natively via `platform()` since 5.0, and Spring's own
documentation now treats the plugin as the legacy option.

While standing up the build we hit a hard failure: with Gradle 9.6.1 and Kotlin 2.3.21, applying
`io.spring.dependency-management` breaks Kotlin's classpath-snapshot artifact transform —

```
Execution failed for ClasspathEntrySnapshotTransform: ...
  > org/jetbrains/kotlin/incremental/classpathDiff/ClasspathEntrySnapshotter$Settings
```

The failure was bisected to that plugin specifically: an otherwise identical build using
`platform()` compiles cleanly. The plugin mutates every configuration in the project, including the
internal detached configurations Kotlin uses for incremental compilation.

## Decision

Use Gradle's native `platform()` BOM everywhere. The Boot BOM is applied once, in the
`finix.spring-service` and `finix.spring-library` convention plugins, to `implementation`,
`testImplementation`, `integrationTestImplementation` and `annotationProcessor`.
`io.spring.dependency-management` is not on the classpath anywhere in this repository.

## Consequences

**Positive.** Incremental Kotlin compilation works. Dependency resolution is standard Gradle with no
plugin-specific semantics, so `dependencies` reports and version-catalog constraints behave
predictably. One less plugin in the supply chain.

**Negative.** `platform()` publishes *constraints*, not forced versions, so a transitive dependency
can still resolve above the BOM version. Where that matters we add an explicit
`resolutionStrategy` rule rather than reaching for the legacy plugin.

**Related.** Kotlin's version is pinned to **2.3.21** to match the Kotlin compiler embedded in
Gradle 9.6.1. Mixing versions across the buildscript classloader of an included build produces the
same class of failure.
