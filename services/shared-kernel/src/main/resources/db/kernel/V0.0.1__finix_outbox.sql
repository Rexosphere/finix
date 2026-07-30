-- The transactional outbox, shipped by shared-kernel so every publishing service has an
-- identical table. Services add `classpath:db/kernel` to spring.flyway.locations; the 0.0.x
-- version prefix keeps kernel migrations ordered ahead of every service's own V1.
--
-- See ADR-0005 for why this is a polled outbox rather than Debezium CDC.

CREATE TABLE outbox_message (
    id            UUID        PRIMARY KEY,
    topic         TEXT        NOT NULL,
    -- Kafka partition key: the aggregate id, so events about one account stay ordered.
    partition_key TEXT        NOT NULL,
    event_type    TEXT        NOT NULL,
    headers       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    attempts      INT         NOT NULL DEFAULT 0,
    last_error    TEXT
);

-- The relay only ever asks for unpublished rows in insertion order. A partial index keeps the
-- hot path proportional to the backlog rather than to the (ever-growing) history: on a table
-- with ten million relayed messages and forty pending, this index holds forty entries.
CREATE INDEX idx_outbox_unpublished
    ON outbox_message (created_at, id)
    WHERE published_at IS NULL;

-- Supports the retention job and dead-letter alerting without scanning the pending index.
CREATE INDEX idx_outbox_published_at ON outbox_message (published_at) WHERE published_at IS NOT NULL;

COMMENT ON TABLE outbox_message IS
    'Transactional outbox: written in the same transaction as the state change it describes, relayed to Kafka at-least-once.';
COMMENT ON COLUMN outbox_message.id IS
    'Also the EventEnvelope.eventId, which consumers use as their deduplication key.';
