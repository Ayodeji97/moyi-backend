-- Media assets, the aggregate (Phase 4 slice D1, ADR-0047): doc 07:235-245,
-- plus the three columns the spec adds (§4 D1): `attached_entry_id`, `tier`
-- and `processing_attempts`.
--
-- Numbered V32, not the spec's V27: main holds V26, V27 and V29, and open pull
-- requests hold V30 and V31. Flyway runs without outOfOrder, so a number below
-- the highest applied would be refused.
--
-- `media` is a leaf (spec §2.1), so nothing here names another module's table:
-- `owner_user_id` is identity's user, `bond_id` and `attached_entry_id` are
-- bond's and gratitude's, and none carries a foreign key (no foreign key
-- crosses a module boundary). `bond_id` is in doc 07's shape and stays empty
-- until something sets it; an asset belongs to a person, and reaches a bond
-- only through an entry (BR-1, spec §4 D1).
--
-- Ids are UUID v4 (doc 06:14): a v7 id carries its creation time, which BR-8
-- suppresses on a locked entry, and a media id will travel with one (D2).
--
-- `storage_key` is `in/{uuid}`: random, carrying no user, bond or kind (doc
-- 09:18). The key is still never logged; it is the one thing that, with the
-- bucket's credentials, reaches the bytes.
CREATE TABLE media_assets (
    id                  uuid        PRIMARY KEY,
    owner_user_id       uuid        NOT NULL,
    bond_id             uuid,
    kind                text        NOT NULL,
    storage_key         text        NOT NULL,
    content_type        text        NOT NULL,
    -- What the owner declared at the intent. The upload URL is signed for
    -- exactly this length, and completion checks the stored object against it.
    size_bytes          bigint      NOT NULL,
    -- SHA-256 of the bytes, base64, as declared and signed into the upload URL.
    checksum            text,
    width               int,
    height              int,
    -- The voice note's length, on the asset only (spec D-10).
    duration_ms         int,
    status              text        NOT NULL,
    moderation_verdict  text,
    moderation_scores   jsonb,
    variants            jsonb       NOT NULL DEFAULT '[]',
    -- The entry this asset is attached to (D2's FR-044a claim). No foreign key:
    -- the entry is gratitude's. A conditional update on this column is what
    -- makes a race for one asset have one winner.
    attached_entry_id   uuid,
    -- 1 while the full-resolution variant is kept, 2 once only the display
    -- variant is (D-16, D5's demotion).
    tier                smallint    NOT NULL DEFAULT 1,
    processing_attempts int         NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    ready_at            timestamptz,
    -- When the upload URL stops working: the intent's deadline.
    expires_at          timestamptz,
    deleted_at          timestamptz,

    CONSTRAINT media_assets_kind_check CHECK (kind IN ('IMAGE', 'VOICE')),
    CONSTRAINT media_assets_status_check CHECK (
        status IN ('PENDING_UPLOAD', 'UPLOADED', 'PROCESSING', 'READY', 'QUARANTINED', 'FAILED', 'DELETED')
    ),
    CONSTRAINT media_assets_tier_check CHECK (tier IN (1, 2)),
    CONSTRAINT media_assets_size_check CHECK (size_bytes > 0),
    CONSTRAINT media_assets_storage_key_key UNIQUE (storage_key),
    -- A deleted asset has a time of deletion, and only a deleted one does.
    CONSTRAINT media_assets_deleted_check CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL))
);

-- The reaper's index (doc 07:244, D5).
CREATE INDEX media_assets_status_created_idx ON media_assets (status, created_at);

-- An owner's assets (doc 07:245): every route here reads by id and owner.
CREATE INDEX media_assets_owner_idx ON media_assets (owner_user_id);
