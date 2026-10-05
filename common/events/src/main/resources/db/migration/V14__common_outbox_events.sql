-- Immutable event history; acknowledgement belongs to each registered consumer (spec §8).
CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    aggregate_type text NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE TABLE outbox_deliveries (
    event_id uuid NOT NULL REFERENCES outbox_events(id),
    consumer_id text NOT NULL,
    processed_at timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    last_error text,
    PRIMARY KEY (event_id, consumer_id)
);
CREATE INDEX outbox_deliveries_pending_idx ON outbox_deliveries (consumer_id, next_attempt_at)
    WHERE processed_at IS NULL;
