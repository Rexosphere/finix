# 5. Transactional outbox poller over Debezium CDC

- Status: Accepted
- Date: 2026-07-30

## Context

Every FINIX service that publishes domain events must keep "state changed" and "event emitted"
as a single atomic fact. Dual-writing to Postgres and Kafka independently loses events on a crash
between the two commits, or emits events for rolled-back transactions.

Two standard answers exist:

1. **Transactional outbox** — write an `outbox_message` row in the same DB transaction as the
   domain change; a relay publishes to Kafka afterwards.
2. **Change-data capture** (Debezium) — stream the WAL into Kafka Connect and derive events from
   committed rows.

Blueprint §3 and the Phase-2 graded target both require a single-command runnable system on a
grader laptop. Debezium needs `wal_level=logical`, a Kafka Connect cluster, and connector
lifecycle management — real Phase-3 infrastructure.

## Decision

Ship a **polled JDBC outbox** in `shared-kernel` (`JdbcOutbox` + `OutboxPublisher`). Every
publishing service enables it by applying `finix.messaging` and adding `classpath:db/kernel` to
Flyway locations. The outbox *table* schema is identical to what a Debezium outbox-event-router
would consume, so production evolution is a deployment change, not a rewrite.

## Consequences

**Positive.** Works with stock Postgres, no Connect cluster, no WAL tuning. Horizontally scalable
via `FOR UPDATE SKIP LOCKED`. At-least-once delivery is explicit and paired with
`EventEnvelope.eventId` for consumer deduplication.

**Negative.** Relay latency is bounded by the poll interval (default 500 ms). A stuck poller
builds backlog — surfaced as the `finix.outbox.pending` gauge.

**Deferred.** Debezium CDC under `--profile cdc` is Phase-3; the fidelity matrix records it.
