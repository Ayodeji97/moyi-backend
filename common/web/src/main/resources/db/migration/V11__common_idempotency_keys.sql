-- Doc 06 §1's idempotency record, owned by `common:web` because doc 05 §2.1
-- puts the idempotency filter there and the table is that filter's state.
--
-- Postgres and not Redis: doc 06 §1 writes the key as `idem:{userId}:{endpoint}:{key}`,
-- which reads as a Redis key, but doc 05 §3 is explicit that idempotency,
-- revocation and ShedLock are Postgres-backed so that Redis stays removable in
-- one session. The Phase 3 design §12.3 settles it that way for both this and
-- ShedLock.
--
-- Versions are one global sequence across modules, because they are one
-- schema: V1-V8 are app's and identity's, V9-V10 are bond's, this is
-- common:web's first — the first migration owned by a module nothing else in
-- the schema references. Forward-only (doc 07 §1) — a mistake here is
-- corrected by V12, never by editing this file.
--
-- No JPA entity maps this table (see IdempotencyKeyStore's KDoc): the row is
-- read and written through plain JDBC, so `ddl-auto: validate` never touches
-- it and no module's own `@EntityScan` needs to widen to see it.
CREATE TABLE idempotency_keys (
    id               uuid        PRIMARY KEY,
    user_id          uuid        NOT NULL,
    endpoint         text        NOT NULL,
    idempotency_key  text        NOT NULL,
    -- SHA-256 of the raw request body. The body itself is never stored: an
    -- entry's text is the thing this system exists not to leak (doc 18 §9).
    request_hash     text        NOT NULL,
    -- Null until the handler returns. A row that exists with a null status is
    -- a request in flight, which is what makes the second one a 409 rather
    -- than a second execution.
    response_status  smallint,
    response_body    text,
    created_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,

    CONSTRAINT idempotency_keys_unique UNIQUE (user_id, endpoint, idempotency_key),
    CONSTRAINT idempotency_keys_expiry_check CHECK (expires_at > created_at)
);

-- The reaper's index, partial so it stays small as the table grows.
CREATE INDEX idempotency_keys_expiry_idx ON idempotency_keys (expires_at);
