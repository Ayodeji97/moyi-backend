-- Identity — the two tables the authentication path reads and writes.
--
-- This migration lives in `modules/identity`, not in `app`: a module owns its
-- own tables, and owning them means owning the statements that create them.
-- The practical consequence is that the module's test suite can stand up its
-- schema by itself, without the application — which it has to, because the
-- entities and repositories under test are `internal` and unreachable from
-- `app`. Flyway merges every `classpath:db/migration` on the classpath, so
-- `app` picks this up through its dependency on the module.
--
-- Versions are one global sequence across all modules, because they are one
-- schema. Two branches that both claim V3 collide loudly — Flyway refuses to
-- start on a duplicate version — rather than silently applying one of them.
-- Columns follow doc 07 §2 "identity" exactly; nothing speculative is added
-- here. The remaining identity tables (refresh_tokens, verification_tokens,
-- devices, consent_records, deletion_requests) arrive with the slice that
-- needs them, so every migration stays a statement about shipped behaviour.
--
-- Forward-only (doc 07 §1): a mistake in this file is corrected by V3, never
-- by editing it. Flyway has already applied it everywhere by then.

-- Repeated from V1, which creates the extensions for the deployed database.
-- Stated again here so this module's schema is self-sufficient on an empty
-- database; `IF NOT EXISTS` makes the second one a no-op.
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id                uuid        PRIMARY KEY,
    -- citext, not text: emails are compared case-insensitively, and doing
    -- that in the column type means the unique index below is case-insensitive
    -- too. Lower-casing in application code instead would both destroy the
    -- address the user typed and leave the constraint case-SENSITIVE, which is
    -- the bug that lets two accounts share one mailbox.
    email             citext      NOT NULL,
    email_verified_at timestamptz,
    display_name      text        NOT NULL,
    avatar_media_id   uuid,
    locale            text        NOT NULL DEFAULT 'en',
    status            text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz,
    deleted_at        timestamptz,

    -- Uniqueness is the account model: one mailbox, one account. Note the
    -- interaction with the soft delete — because this index covers every row,
    -- a deleted user's address can never be registered again while the row
    -- survives. That is deliberate rather than overlooked: the account
    -- deletion path (Phase 5) must scrub the address, and this constraint is
    -- what makes forgetting to do so fail loudly instead of silently.
    CONSTRAINT users_email_key UNIQUE (email),

    -- Bounds, not validation. The shape of an address is the domain's job
    -- (`Email`) and the request layer's; what the database owes is a limit on
    -- what can be stored, so an unbounded `text` column is not a way to write
    -- ten megabytes into a row. 254 is the RFC 5321 maximum for a path.
    CONSTRAINT users_email_length_check CHECK (char_length(email) BETWEEN 3 AND 254),
    CONSTRAINT users_display_name_length_check CHECK (char_length(display_name) BETWEEN 1 AND 80),

    -- `status text` per doc 07, not a Postgres enum: adding a value to a PG
    -- enum is a schema change with awkward transactional rules, while widening
    -- a CHECK is an ordinary migration. The CHECK is the part that matters —
    -- it is what turns a mapper bug into a failed insert instead of a row
    -- nobody can interpret.
    CONSTRAINT users_status_check CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'PENDING_DELETION', 'DELETED')
    )
);

-- Separated from `users` so that `password_hash` cannot be selected by a query
-- that had no business reading it (doc 07 §2). One row per user, keyed by the
-- user id itself — there is no second credential per account to disambiguate,
-- so a surrogate id would only add a column to join on.
CREATE TABLE credentials (
    user_id             uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    password_hash       text        NOT NULL,
    -- Recorded per row so a password hashed under older parameters can be
    -- re-hashed on the next successful login rather than in a mass migration
    -- that would need the plaintext. Widening this CHECK is the one-line
    -- migration that makes adopting a new algorithm a reviewed decision.
    algorithm           text        NOT NULL,
    password_updated_at timestamptz NOT NULL,
    failed_attempts     int         NOT NULL DEFAULT 0,
    locked_until        timestamptz,

    CONSTRAINT credentials_algorithm_check CHECK (algorithm IN ('ARGON2ID')),
    CONSTRAINT credentials_failed_attempts_check CHECK (failed_attempts >= 0)
);
