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
-- schema: V1 is app's, V2-V8 are identity's, V9 is bond's, this is
-- common:web's first — the first migration owned by a module nothing else in
-- the schema references. V10 is reserved by other work not yet merged into
-- this branch; Flyway tolerates the gap (see FlywayMigrationTest). Versions
-- are forward-only (doc 07 §1) — a mistake here is corrected by a later
-- migration in this slice, never by editing this file, unless the review
-- that catches the mistake lands before this one merges (as the review that
-- produced Ruling A and Ruling B below did).
--
-- No JPA entity maps this table (see IdempotencyKeyStore's KDoc): the row is
-- read and written through plain JDBC, so `ddl-auto: validate` never touches
-- it and no module's own `@EntityScan` needs to widen to see it.
--
-- Ruling A (review round 1): doc 06 §1 contradicts itself — "keyed on userId
-- + endpoint + key" and "the same key against a different endpoint is 422"
-- cannot both hold, because a key that includes the endpoint makes a
-- different endpoint a different row, which never conflicts with anything.
-- Decided in favour of the observable contract: the UNIQUE constraint below
-- is `(user_id, idempotency_key)` only, `endpoint` is a stored column
-- compared in code (see IdempotencyInterceptor.replay), and a mismatch on
-- either `endpoint` or `request_hash` is `422 IDEMPOTENCY_KEY_REUSED`.
--
-- Ruling B (review round 1): the 24h window was written nowhere and enforced
-- nowhere. It is enforced at read — IdempotencyKeyStore adds
-- `expires_at > :now` to its lookup, the same pattern V4's
-- verification_tokens, V6's refresh_tokens and V9's bond_invites already
-- use — rather than by a scheduled reaper, which belongs to slice C3
-- (ShedLock) and does not exist yet. Until it does, an expired row can still
-- physically block a fresh reservation under the UNIQUE constraint above;
-- IdempotencyKeyStore.reserve reclaims it in that one case rather than
-- letting a legitimate 24-hours-later reuse of the same key become a 500.
CREATE TABLE idempotency_keys (
    id               uuid        PRIMARY KEY,
    user_id          uuid        NOT NULL,
    -- `METHOD path`, e.g. `POST /api/v1/entries` — stored, not part of the
    -- key (Ruling A above), and compared against the endpoint of a second
    -- request under the same key.
    endpoint         text        NOT NULL,
    idempotency_key  text        NOT NULL,
    -- SHA-256 of the raw request body. The body itself is never stored: an
    -- entry's text is the thing this system exists not to leak (doc 18 §9).
    request_hash     text        NOT NULL,
    -- Null until the handler returns. A row that exists with a null status is
    -- a request in flight, which is what makes the second one a 409 rather
    -- than a second execution.
    response_status  smallint,
    -- The whole response body, so a replay can return exactly what the first
    -- attempt did. This is a bounded-lifetime DUPLICATE of data that lives
    -- elsewhere already — for `POST /entries`, the entry's own text — never
    -- the row of record: Ruling B's expires_at > :now bounds how long a copy
    -- sits here to 24 hours, and slice C3's reaper clears it once it lands.
    -- Written in the clear, on purpose, for now: **Phase 5 encrypts
    -- entries.text at rest, and this column would still hold that same text
    -- in plain text unless Phase 5 also touches this table** — flagged here
    -- so that work finds it by reading this comment, not by an audit.
    response_body    text,
    -- Doc 06 §1's replay allowlist (review round 1, Important #2): a stored
    -- response is useless without the headers a client needs back —
    -- `ETag` for a resource this project puts optimistic concurrency on
    -- (doc 06 §1, ADR-0029), `Location` for what a 201 normally carries.
    -- An explicit pair of columns rather than one `response_headers` blob:
    -- these are the only two headers any endpoint in this codebase sets that
    -- a client cannot rebuild from the body, so there is nothing generic to
    -- gain from storing an arbitrary map, and a fixed pair is trivial to
    -- read back without a parser.
    response_etag    text,
    response_location text,
    created_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,

    CONSTRAINT idempotency_keys_unique UNIQUE (user_id, idempotency_key),
    CONSTRAINT idempotency_keys_expiry_check CHECK (expires_at > created_at)
);

-- The reaper's index (slice C3, not built here): a plain index, not
-- partial, because a reaper needs to find every row whose window has
-- passed — live or already consumed — and there is no subset of rows to
-- exclude with a WHERE clause the way `bond_invites_live_code_idx` excludes
-- spent invites.
CREATE INDEX idempotency_keys_expiry_idx ON idempotency_keys (expires_at);
