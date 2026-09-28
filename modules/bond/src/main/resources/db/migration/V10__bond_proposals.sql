-- Two-party consent — FR-027 (the anchor timezone), FR-028 (deletion),
-- BR-6, doc 04 §8.5, ADR-0030.
--
-- One table for both, because they are one mechanism: somebody proposes, the
-- other member confirms within seven days, either may cancel, and a proposal
-- nobody answers simply lapses. Two tables would be two copies of that rule,
-- and the second copy is where they would drift apart.
--
-- Bond's is the second module to add a migration and the versions are one
-- global sequence (V1 app, V2–V8 identity, V9 bond, this). Forward-only
-- (doc 07 §1): a mistake here is corrected by V11, never by editing this file.
--
-- `bond_proposals` is new in doc 07 §2 — the document describes the *rules*
-- for FR-027 and FR-028 without naming a table; ADR-0030 records the shape.

CREATE TABLE bond_proposals (
    id                     uuid        PRIMARY KEY,
    bond_id                uuid        NOT NULL REFERENCES bonds (id) ON DELETE CASCADE,
    kind                   text        NOT NULL,
    -- What is being proposed, as text because only one kind has anything to
    -- carry: an IANA region id for TIMEZONE_CHANGE, NULL for DELETION. A jsonb
    -- column would invite a second field nobody has specified yet.
    payload                text,
    proposed_by_member_id  uuid        NOT NULL REFERENCES bond_members (id) ON DELETE CASCADE,
    proposed_at            timestamptz NOT NULL,
    -- Seven days (FR-027, and states.md §8 tells the member so). A lapsed
    -- proposal is not reaped on a schedule: every read has `expires_at` in its
    -- predicate, and a lapsed row is closed lazily when a new proposal of the
    -- same kind needs its slot (ADR-0030).
    expires_at             timestamptz NOT NULL,
    -- Who agreed, and when. BR-6: never the proposer.
    confirmed_by_member_id uuid        REFERENCES bond_members (id) ON DELETE CASCADE,
    confirmed_at           timestamptz,
    cancelled_at           timestamptz,

    CONSTRAINT bond_proposals_kind_check CHECK (kind IN ('TIMEZONE_CHANGE', 'DELETION')),
    -- A bound rather than validation: the longest tzdb id is 32 characters and
    -- the shape of a zone is the domain's job (RegionZone).
    CONSTRAINT bond_proposals_payload_length_check
        CHECK (payload IS NULL OR char_length(payload) BETWEEN 1 AND 64),
    -- A confirmation is a member and a time, together or not at all.
    CONSTRAINT bond_proposals_confirmed_pair_check
        CHECK ((confirmed_at IS NULL) = (confirmed_by_member_id IS NULL)),
    -- Confirmed or cancelled, never both: those are the two ways it ends.
    CONSTRAINT bond_proposals_one_ending_check
        CHECK (confirmed_at IS NULL OR cancelled_at IS NULL)
);

-- One open proposal per kind per bond (FR-027, FR-028). Partial, so a bond may
-- hold any number of *closed* ones — the record of what was asked and what came
-- of it, which doc 26 §4 would want if a safety question ever arrived.
--
-- Note what this index cannot know: a lapsed proposal is still "open" by its
-- definition, because an index cannot ask what time it is. That is exactly why
-- proposing closes a lapsed one first (ADR-0030) rather than relying on the
-- index to have forgotten it.
CREATE UNIQUE INDEX bond_proposals_open_kind_key
    ON bond_proposals (bond_id, kind)
    WHERE confirmed_at IS NULL AND cancelled_at IS NULL;

-- Reading a bond with whatever is pending on it: the query on the front of
-- GET /bonds and GET /bonds/{id}.
CREATE INDEX bond_proposals_bond_id_idx ON bond_proposals (bond_id);
