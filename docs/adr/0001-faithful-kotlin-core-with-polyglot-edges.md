# 1. Faithful Spring Boot 3 + Kotlin core, polyglot at the edges

- Status: Accepted
- Date: 2026-07-29
- Deciders: Team Rexosphere

## Context

The Phase 1 RECON blueprint (§7.2) commits FINIX to a polyglot stack: Spring Boot 3 + Kotlin for
core banking, Go for the Payment Hub, Node.js for notifications, Python FastAPI for the AI service.
The stated justification (§7.6) is blast-radius containment — "if one language runtime is exploited,
others survive".

Phase 2 is graded partly on *consistency with the Phase 1 architecture*, so silently collapsing to a
single-language monorepo would be cheaper to build but would contradict the submitted design.

## Decision

Implement the blueprint's stack faithfully:

- **Kotlin 2.3 + Spring Boot 3.5 on JDK 21** for the nine core banking services.
- **Go** for `payment-hub` — the blueprint's concurrency claim, and the service that fans out to
  external rails.
- **Python FastAPI** for `risk-ai-service` — where the ML ecosystem actually lives.
- **Node.js** for `notification-service` — templating and channel fan-out.

Every Kotlin service follows the same hexagonal package shape and is produced by shared Gradle
convention plugins in `services/build-logic/`, so "nine services" costs far less than nine
independent builds.

## Consequences

**Positive.** The polyglot resilience argument in the blueprint is demonstrably real, not
aspirational. JVM virtual threads carry the blocking JDBC/HTTP paths in the banking core, which is
the right runtime for transactional workloads. Strong static typing on the money paths.

**Negative.** Four toolchains to install, build and scan in CI. Cross-language contract drift is a
real risk, mitigated by generating clients from committed OpenAPI specs and by Pact contract tests.
Ten JVMs is heavy for a grader's laptop — mitigated by Compose profiles and small serial-GC heaps.

**Rejected alternative.** An all-TypeScript monorepo would have been roughly 40% faster to build and
easier to keep consistent, but it abandons a headline Phase 1 claim, which costs more marks under
"System Architecture & best practices" than it saves under "Solution's functionality".
