-- ADR-0021, amended 2026-11-08 (issue #136): the refresh grace window.
--
-- A retry of a rotated token inside the window voids the successor it was
-- never given. A voided token is marked rotated at the moment it was voided,
-- so every "live" query already leaves it out and presenting it is a reuse;
-- `voided_at` is what tells it apart from a token its holder actually used,
-- because a voided token must never itself be eligible for the grace retry.
-- It has no successor of its own: it was never used.
ALTER TABLE refresh_tokens ADD COLUMN voided_at timestamptz;

ALTER TABLE refresh_tokens
    ADD CONSTRAINT refresh_tokens_voided_is_rotated_check
    CHECK (voided_at IS NULL OR (rotated_at = voided_at AND replaced_by IS NULL));
