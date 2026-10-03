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
-- schema: V1 is app's, V2-V8 are identity's, V9 and V10 are bond's, this is
-- common:web's first — the first migration owned by a module nothing else in
-- the schema references. V10 has since arrived (bond's proposals), so there
-- is no gap here any more. Versions
-- are forward-only (doc 07 §1) — a mistake here is corrected by a later
-- migration in this slice, never by editing this file, unless the review
-- that catches the mistake lands before this one merges (as the review that
-- produced Ruling A and Ruling B below did).
--
-- No JPA entity maps this table (see IdempotencyKeyStore's KDoc): the row is
-- read and written through plain JDBC, so `ddl-auto: validate` never touches
-- it and no module's own `@EntityScan` needs to widen to see it.
--
-- Ruling A (review round 1, restated for `method`/`path`): doc 06 §1
-- contradicts itself — "keyed on userId + endpoint + key" and "the same key
-- against a different endpoint is 422" cannot both hold, because a key that
-- includes the endpoint makes a different endpoint a different row, which
-- never conflicts with anything. Decided in favour of the observable
-- contract: the UNIQUE constraint below is `(user_id, idempotency_key)` only,
-- `method` and `path` are stored columns compared in code (see
-- IdempotentExecution.replay), and a mismatch on `method`, `path` or
-- `request_hash` is `422 IDEMPOTENCY_KEY_REUSED`. `path` is the request's
-- CONCRETE path, never a route template: `/api/v1/bonds/{bondId}/entries`
-- would make two bonds one target, and a key replayed against a different
-- bond must be refused, not answered from the first bond's result (spec
-- §5.4).
--
-- Ruling B (review round 1): the 24h window was written nowhere and enforced
-- nowhere. It is enforced at read — IdempotencyKeyStore adds
-- `expires_at > :now` to its lookup, the same pattern V4's
-- verification_tokens, V6's refresh_tokens and V9's bond_invites already
-- use — rather than by a scheduled reaper, which belongs to slice C3
-- (ShedLock) and does not exist yet. Until it does, an expired row can still
-- physically occupy the UNIQUE constraint below; IdempotentExecution deletes
-- it before reserving the key again, under the same advisory lock (spec §5.4).
--
-- Spec §5.4's one transaction (plan task 7): a row is written and completed
-- inside the SAME transaction as the write it guards, under a nonblocking
-- advisory lock on (user_id, key) taken before the lookup. So to every other
-- transaction a row is either absent or complete, and a request that fails —
-- a refusal or a crash — rolls its reservation back with its write, leaving
-- nothing to block a corrected retry. Only successes are ever recorded.
CREATE TABLE idempotency_keys (
    id               uuid        PRIMARY KEY,
    user_id          uuid        NOT NULL,
    -- The request's own method and **concrete** path, e.g. `POST` and
    -- `/api/v1/bonds/3f2a.../entries` — stored, not part of the key (Ruling A
    -- above), and compared against a second request under the same key. A
    -- route template is not sufficient: two bonds are two different targets.
    method           text        NOT NULL,
    path             text        NOT NULL,
    idempotency_key  text        NOT NULL,
    -- A KEYED fingerprint (HMAC-SHA256 under the server's personal-data
    -- secret, ruling P8) of method, path and raw body together — never a
    -- plain hash. The body itself is never stored: an entry's text is the
    -- thing this system exists not to leak (doc 18 §9), and an unkeyed
    -- SHA-256 of a short entry would be recoverable from this column by
    -- hashing guesses for the row's whole 24 hours. See RequestFingerprint.
    request_hash     text        NOT NULL,
    -- Null only between the reservation and its completion, which happen in
    -- one transaction, so no other transaction ever sees it null. "In flight"
    -- is the advisory lock's answer (409), not this column's.
    response_status  smallint,
    -- What the first attempt produced, by IDENTITY — never a second copy of
    -- what it produced. An earlier shape of this table stored the whole
    -- response body here, a plaintext duplicate of the couple's words for 24
    -- hours; this shape retires that hazard, and there is no body column to
    -- encrypt, clear or forget in Phase 5 or in slice C3's reaper.
    result_id        uuid,
    -- Which resource `result_id` names, so a replay knows what to re-read.
    result_kind      text,
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
    CONSTRAINT idempotency_keys_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT idempotency_keys_result_kind_check CHECK (result_kind IS NULL OR result_kind IN ('ENTRY')),
    -- A result is named by both halves or by neither: an id with no kind
    -- cannot be routed to a re-read, and a kind with no id names nothing.
    CONSTRAINT idempotency_keys_result_pair_check CHECK ((result_id IS NULL) = (result_kind IS NULL))
);

-- The reaper's index (slice C3, not built here): a plain index, not
-- partial, because a reaper needs to find every row whose window has
-- passed — live or already consumed — and there is no subset of rows to
-- exclude with a WHERE clause the way `bond_invites_live_code_idx` excludes
-- spent invites.
CREATE INDEX idempotency_keys_expiry_idx ON idempotency_keys (expires_at);
