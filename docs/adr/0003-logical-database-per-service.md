# 3. Logical database-per-service by default, physical under a profile

- Status: Accepted
- Date: 2026-07-29

## Context

Blueprint §3.1 Layer 4 specifies "Database per Service". The purist reading is one PostgreSQL
*container* per service. With nine Kotlin services plus Kafka, Redis, Keycloak, Vault, Kong and the
observability stack, that is roughly 20 containers before any application code runs — more than a
grader's laptop can comfortably host, and `make demo` must work in under ten minutes on a cold
clone.

The property that actually matters architecturally is **no shared schema and no cross-service
reads**, not process isolation of the database engine.

## Decision

Default (`docker compose up`): a single PostgreSQL server hosting **one database per service**, each
with its **own role**, its own Flyway migration history, and **no grants across databases**. A
service physically cannot read another service's tables.

Under `--profile isolated-db`: one PostgreSQL container per service, identical schemas and
migrations, for demonstrating the production topology.

Production (`infra/k8s`, `infra/terraform`): separate managed instances per service.

## Consequences

**Positive.** The invariant judges care about — services own their data and integrate over APIs and
events, never over a shared table — is enforced and testable in the default profile. Startup stays
within the ten-minute budget.

**Negative.** The default profile shares a failure domain: one Postgres process going down affects
every service. This is exactly what the chaos experiment in `tests/chaos` exercises, and the
`isolated-db` profile exists to show the intended production separation.

**Enforcement.** An integration test asserts that each service's database role is denied `SELECT`
on every other service's tables, so this stays a real boundary rather than a convention.
