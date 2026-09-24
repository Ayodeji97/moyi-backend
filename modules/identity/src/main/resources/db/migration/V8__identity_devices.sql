-- Devices (doc 07 §2, doc 04 §Device), built with FR-007 as ADR-0020 said they
-- would be. A device is what a sign-in described itself as; a session is a
-- live refresh-token family, and the family points at the device that
-- started it.
--
-- `push_token` is here because doc 07 puts it here; nothing fills it until
-- Phase 3's notifications. Until then two sign-ins from the same phone are
-- two device rows, because without a push token or an installation id there
-- is nothing to say they are one — ADR-0025 records that as the interim.
CREATE TABLE devices (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Doc 04's enum, as text plus a CHECK like every other status column.
    platform     text        NOT NULL,
    push_token   text,
    app_version  text        NOT NULL,
    os_version   text        NOT NULL,
    last_seen_at timestamptz NOT NULL,
    revoked_at   timestamptz,

    CONSTRAINT devices_platform_check CHECK (platform IN ('ANDROID', 'IOS', 'WEAR')),
    CONSTRAINT devices_app_version_length_check CHECK (char_length(app_version) BETWEEN 1 AND 40),
    CONSTRAINT devices_os_version_length_check CHECK (char_length(os_version) BETWEEN 1 AND 40),
    CONSTRAINT devices_push_token_length_check CHECK (push_token IS NULL OR char_length(push_token) <= 512),
    -- Doc 07: one row per push token per user, once push tokens exist.
    CONSTRAINT devices_user_push_token_key UNIQUE (user_id, push_token)
);

-- "My sessions" is every live family of one user joined to its device.
CREATE INDEX devices_user_id_idx ON devices (user_id);

-- The interim column ADR-0020 deferred goes. Nothing is deployed, so there is
-- no data to carry across and expand/contract (doc 07 §6) does not apply;
-- ADR-0025 says so rather than leaving a dead column to explain later.
ALTER TABLE refresh_tokens DROP CONSTRAINT refresh_tokens_device_info_length_check;
ALTER TABLE refresh_tokens DROP COLUMN device_info;
-- Nullable: a client may sign in without describing itself, and that session
-- is still a session. ON DELETE SET NULL rather than CASCADE: a device row
-- going away must not silently end a family; revocation is explicit.
ALTER TABLE refresh_tokens ADD COLUMN device_id uuid REFERENCES devices (id) ON DELETE SET NULL;
