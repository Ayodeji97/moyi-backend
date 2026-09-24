-- Opaque refresh credentials. Only SHA-256 digests are persisted; the
-- bearer secret is returned once and cannot be reconstructed from this table.
CREATE TABLE refresh_tokens (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id    uuid        NOT NULL,
    token_hash   text        NOT NULL,
    issued_at    timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    rotated_at   timestamptz,
    revoked_at   timestamptz,
    replaced_by  uuid,
    device_info  text,

    CONSTRAINT refresh_tokens_hash_key UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_hash_length_check CHECK (char_length(token_hash) = 64),
    CONSTRAINT refresh_tokens_device_info_length_check CHECK (device_info IS NULL OR char_length(device_info) <= 200)
);

CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_expires_at_idx ON refresh_tokens (expires_at);
