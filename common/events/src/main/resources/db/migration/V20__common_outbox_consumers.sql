-- Who receives what (spec §8, C5a). A delivery row is written when its event is
-- published, one per subscription of that event's type, so nothing ever has to
-- scan for "events with no delivery yet" and nothing depends on event ids being
-- in commit order.
CREATE TABLE outbox_consumers (
    consumer_id   text        PRIMARY KEY CHECK (char_length(consumer_id) BETWEEN 1 AND 100),
    registered_at timestamptz NOT NULL
);

-- One row per (consumer, event type). Its presence is what makes a publisher
-- write a delivery; its absence, at registration, is what triggers the backfill.
CREATE TABLE outbox_subscriptions (
    consumer_id   text        NOT NULL REFERENCES outbox_consumers (consumer_id),
    event_type    text        NOT NULL,
    subscribed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer_id, event_type)
);
-- The publisher's lookup, on every publish: "who subscribes to this type".
CREATE INDEX outbox_subscriptions_by_type_idx ON outbox_subscriptions (event_type);

-- V14 left consumer_id free text because no consumer existed. A delivery for a
-- consumer nobody registered could never be claimed, so it is now refused.
ALTER TABLE outbox_deliveries
    ADD CONSTRAINT outbox_deliveries_consumer_fk FOREIGN KEY (consumer_id) REFERENCES outbox_consumers (consumer_id);
