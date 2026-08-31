-- citext: case-insensitive text, used for email columns (Phase 1 identity).
-- pgcrypto: gen_random_uuid() and friends, used for UUID v7/v4 generation.
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
