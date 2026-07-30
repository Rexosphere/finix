# Architecture Decision Records

Every decision that a judge, a teammate, or a future maintainer might reasonably challenge is
recorded here in [MADR](https://adr.github.io/madr/) format. An ADR is immutable once merged:
if a decision changes, a new ADR supersedes it rather than editing history.

| ID | Decision | Status |
|----|----------|--------|
| [0001](0001-faithful-kotlin-core-with-polyglot-edges.md) | Faithful Spring Boot 3 + Kotlin core, polyglot at the edges | Accepted |
| [0002](0002-native-platform-bom-over-dependency-management-plugin.md) | Gradle native `platform()` BOM, not `io.spring.dependency-management` | Accepted |
| [0003](0003-logical-database-per-service.md) | Logical database-per-service by default, physical under a profile | Accepted |
| [0004](0004-ml-dsa-anchor-signatures-instead-of-bls.md) | ML-DSA-65 anchor signatures instead of BLS aggregation | Accepted |
| [0005](0005-transactional-outbox-over-debezium.md) | Transactional outbox poller over Debezium CDC | Accepted |
