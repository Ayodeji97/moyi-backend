-- A six-digit code beside the link (#135, ADR-0018 amended 2026-11-08).
--
-- The code lives on the token's own row, so the link and the code are one
-- credential: spending either sets consumed_at, and a consumed row's code is
-- no longer live. There is no state in which one has been used and the other
-- still works.
--
-- code_hash is HMAC-SHA256 over the configured hashing secret (ADR-0023
-- decision 7), never the code: a million codes are hashed in a second, so an
-- unkeyed digest would be the code. The MAC's input names the row and the
-- purpose, so two rows holding the same code hold different hashes.
--
-- A row stops holding a code when a newer one is issued for the same person
-- and purpose (both columns set to NULL), which is how a new email retires the
-- old code while its link stays live (ADR-0018 decision 6). Rows written before
-- this migration hold none.
ALTER TABLE verification_tokens
    ADD COLUMN code_hash       text,
    ADD COLUMN code_expires_at timestamptz,
    -- Wrong codes presented against this row. Five burns it (the domain's
    -- VerificationToken.MAX_WRONG_CODES); counted under a row lock.
    ADD COLUMN code_attempts   integer NOT NULL DEFAULT 0;

ALTER TABLE verification_tokens
    ADD CONSTRAINT verification_tokens_code_hash_and_expiry_check
        CHECK ((code_hash IS NULL) = (code_expires_at IS NULL)),
    ADD CONSTRAINT verification_tokens_code_attempts_check
        CHECK (code_attempts >= 0);

-- At most one row per person and purpose holds a code. The code check locks
-- that row by (user_id, purpose); this index is what makes "that row" one row,
-- and the issue path retires the old code before it inserts the new one.
CREATE UNIQUE INDEX verification_tokens_one_code_idx
    ON verification_tokens (user_id, purpose)
    WHERE code_hash IS NOT NULL;
