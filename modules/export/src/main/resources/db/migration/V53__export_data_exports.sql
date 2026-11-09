-- Data export (FR-009, FR-094, issue #126, ADR-0059): a person asks for a copy
-- of everything they are entitled to, a job builds it into a zip in the media
-- storage, and a signed URL to it is good for 24 hours.
--
-- A row is the request and, once built, the pointer to the archive. It holds
-- ids, instants, a size and an opaque storage key, and nothing anyone wrote:
-- the archive's contents live only in the stored object, which is deleted when
-- the export expires and when the account is deleted (ADR-0058, ADR-0059).
--
-- Doc 04 lists a `MediaAsset.kind` of `EXPORT`. The archive is NOT a
-- `media_assets` row (ADR-0059 decision 5): every lifecycle rule `media` runs
-- over that table (moderation, demotion, the reaper, report holds) would have
-- to learn to leave it alone, and none of them applies to it.
CREATE TABLE data_exports (
    id            uuid        PRIMARY KEY,
    -- No foreign key: `users` is identity's (CLAUDE.md, no FK across modules).
    user_id       uuid        NOT NULL,
    status        text        NOT NULL,
    requested_at  timestamptz NOT NULL,
    -- How many times the job has started building it; a build past the
    -- third is given up as FAILED.
    attempts      int         NOT NULL DEFAULT 0,
    -- A build in progress holds the row until then, so a second runner, or the
    -- next run after a crash, does not start it again before it could finish.
    claimed_until timestamptz,
    ready_at      timestamptz,
    expires_at    timestamptz,
    -- The object's key in the media storage (`x/<random>.zip`), never the
    -- export's id; null except while READY.
    storage_key   text,
    size_bytes    bigint,
    ended_at      timestamptz,

    CONSTRAINT data_exports_status_check CHECK (status IN ('PREPARING', 'READY', 'EXPIRED', 'FAILED')),
    -- A READY export has its object, its times and its size; nothing else has an object.
    CONSTRAINT data_exports_ready_check CHECK (
        (status = 'READY') = (storage_key IS NOT NULL)
        AND (status <> 'READY' OR (ready_at IS NOT NULL AND expires_at IS NOT NULL AND size_bytes IS NOT NULL))
    ),
    -- EXPIRED and FAILED say when they became so.
    CONSTRAINT data_exports_ended_check CHECK ((status IN ('EXPIRED', 'FAILED')) = (ended_at IS NOT NULL)),
    CONSTRAINT data_exports_attempts_check CHECK (attempts >= 0)
);

-- One export being prepared per person: a second POST while one is being
-- prepared is the same export, and this is what makes two at once one row.
CREATE UNIQUE INDEX data_exports_one_preparing_key
    ON data_exports (user_id)
    WHERE status = 'PREPARING';

-- GET /me/export (the latest) and the account deletion's erasure.
CREATE INDEX data_exports_user_idx ON data_exports (user_id, requested_at DESC);

-- The job's two walks: what to build, oldest first, and what has expired.
CREATE INDEX data_exports_preparing_idx ON data_exports (requested_at) WHERE status = 'PREPARING';
CREATE INDEX data_exports_ready_idx ON data_exports (expires_at) WHERE status = 'READY';
