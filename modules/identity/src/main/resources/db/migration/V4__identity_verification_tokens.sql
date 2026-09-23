-- Verification tokens (FR-002) — the single-use secrets a person is emailed
-- to prove they control an address. Columns follow doc 07 §2 exactly.
--
-- Only the SHA-256 of the secret is stored (doc 18 §9: never a token in
-- plaintext, anywhere). The plaintext exists in memory for the milliseconds
-- between generation and the email, and in the email. A database read — a
-- backup, a leaked dump, an over-broad query — yields hashes that cannot be
-- presented, which is the entire point of hashing a bearer credential. The
-- hash is unsalted, and that is correct here for the reason it would be wrong
-- for a password: the input is 256 bits of CSPRNG output, so there is no
-- dictionary to run against it and nothing for a salt to defend.
--
-- `purpose` is text plus a CHECK, like users.status. Password reset (FR-004)
-- and email change (FR-005) widen the CHECK when they arrive; the table is
-- shared because the lifecycle — issue, present once, expire — is identical.

CREATE TABLE verification_tokens (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose     text        NOT NULL,
    token_hash  text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    -- Set when the token is presented. A consumed token is kept, not deleted,
    -- until the reaper takes it (doc 07 §7): the row answers "how long did
    -- verification take", and a second presentation of a consumed token can
    -- be told apart from a token that never existed.
    consumed_at timestamptz,

    -- The lookup key. Unique by construction — a collision means the
    -- generator is broken — and the constraint is what makes it an index.
    CONSTRAINT verification_tokens_token_hash_key UNIQUE (token_hash),
    -- 64 hex characters: SHA-256. A different length is a different digest,
    -- which would never match anything the service computes.
    CONSTRAINT verification_tokens_token_hash_length_check CHECK (char_length(token_hash) = 64),
    CONSTRAINT verification_tokens_purpose_check CHECK (purpose IN ('EMAIL_VERIFICATION'))
);

-- Two things read by user: retiring a person's other live tokens when one of
-- them is used, and the ON DELETE CASCADE above — which, without an index on
-- the referencing column, is a sequential scan of this table for every
-- deleted user.
CREATE INDEX verification_tokens_user_id_idx ON verification_tokens (user_id);
