-- Consent records (FR-011) — what a person agreed to at registration, which
-- version of it, and when.
--
-- A table rather than three booleans on `users`, and specifically because of
-- FR-009: a row appears in the data export automatically, where a column would
-- have needed someone to remember it. Four documents required the 18+
-- confirmation to be "recorded at registration" and no storage for it existed
-- anywhere until FR-011 settled this shape.

CREATE TABLE consent_records (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Same reasoning as users.status: text plus a CHECK, so widening the set
    -- is an ordinary migration rather than a Postgres enum alteration.
    document        text        NOT NULL,
    version         text        NOT NULL,
    accepted_at     timestamptz NOT NULL,

    -- Corroborating evidence, deliberately nullable and NOT populated yet.
    -- Behind a reverse proxy the client address is whatever X-Forwarded-For
    -- claims, which anyone can set, unless the proxy is trusted and the header
    -- validated. A column filled with an unvalidated value looks like evidence
    -- and is not — which is worse than an empty one. Populated by the slice
    -- that adds the trusted-proxy resolver, which per-IP rate limiting
    -- (FR-012) needs anyway. Hashed, not raw: an IP is personal data and this
    -- row is kept for as long as the account is.
    ip_hash         text,
    user_agent_hash text,

    CONSTRAINT consent_records_document_check CHECK (document IN ('TERMS', 'PRIVACY', 'AGE_18')),
    CONSTRAINT consent_records_version_length_check CHECK (char_length(version) BETWEEN 1 AND 40),

    -- The same person cannot accept the same version of the same document
    -- twice. A *new* version is a new row, which is what makes the history
    -- readable: the rows are the timeline.
    CONSTRAINT consent_records_user_document_version_key UNIQUE (user_id, document, version)
);

-- FR-009 exports a user's whole record, and every consent query is "this
-- person's consents". The unique constraint above indexes
-- (user_id, document, version), whose leading column is user_id — so it
-- already serves that lookup and a second index would be dead weight.
