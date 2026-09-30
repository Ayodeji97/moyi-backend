-- Which IANA zone was *effective* for a bond over which span of UTC time —
-- doc 04 §6, BR-6, ADR-0030, and the Phase 3 design §3.1 as revised.
--
-- `bonds.anchor_timezone` is the zone the bond currently *requests*. This
-- table is the zone that currently *decides dates*, and they differ for up to
-- one logical day after a change is confirmed. B5 writes the request the
-- instant consent completes (ChangeTimezone.confirm); this table is what
-- defers the effect, because BR-6 says an approved change takes effect from
-- the next Bond-day and never recomputes an existing one.
--
-- Why a table and not a column: on a day nobody has written to, there is no
-- `bond_days` row to have copied a zone onto, so a copied string cannot defer
-- anything. The spec says so in as many words — "copying a string at lazy row
-- creation is insufficient".
--
-- Contiguous and non-overlapping by construction: each row's effective_from
-- is the previous row's effective_to, and exactly one row per bond has a null
-- effective_to. The partial unique index below enforces the "exactly one
-- open" half; contiguity is the domain's invariant (AnchorTimeline's `init`).
--
-- Versions are one global sequence across modules: V1 app, V2-V8 identity,
-- V9-V10 bond, V11 common:web, V12 gratitude, V13 bond again. Forward-only
-- (doc 07 §1) once merged.
CREATE TABLE bond_anchor_intervals (
    id             uuid        PRIMARY KEY,
    bond_id        uuid        NOT NULL REFERENCES bonds (id) ON DELETE CASCADE,
    -- An IANA region id, validated in the domain (`RegionZone`), bounded here
    -- the same way `bonds.anchor_timezone` is.
    zone           text        NOT NULL,
    -- The first calendar label this interval issues. With contiguity, the
    -- labels a bond has ever used are exactly the run from the earliest
    -- interval's first_label through today's — so "has this label been used"
    -- is answerable inside `bond`, with no read of `gratitude`'s bond_days
    -- and no dependency between the modules (doc 25 §6, ADR-0026).
    first_label    date        NOT NULL,
    effective_from timestamptz NOT NULL,
    -- Null for the one open interval per bond.
    effective_to   timestamptz,
    created_at     timestamptz NOT NULL,

    CONSTRAINT bond_anchor_intervals_zone_length_check CHECK (char_length(zone) BETWEEN 1 AND 64),
    CONSTRAINT bond_anchor_intervals_order_check CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- Exactly one open interval per bond. A second one would make "which zone is
-- effective now" ambiguous, which is the one question this table exists to
-- answer without ambiguity.
CREATE UNIQUE INDEX bond_anchor_intervals_open_key
    ON bond_anchor_intervals (bond_id) WHERE effective_to IS NULL;
-- The timeline load: every interval for one bond, oldest first.
CREATE INDEX bond_anchor_intervals_bond_idx ON bond_anchor_intervals (bond_id, effective_from);

-- Backfill. Every bond that already exists has been deciding dates by
-- `bonds.anchor_timezone` since it was created, and no change has ever been
-- deferred, so its whole history is one open interval in its current zone.
-- `gen_random_uuid()` rather than an application-assigned v7 id: this runs
-- once, for rows nothing is holding a reference to, and pgcrypto is already
-- an extension this schema installs (V1).
INSERT INTO bond_anchor_intervals (id, bond_id, zone, first_label, effective_from, effective_to, created_at)
SELECT gen_random_uuid(), b.id, b.anchor_timezone, (b.created_at AT TIME ZONE b.anchor_timezone)::date, b.created_at, NULL, b.created_at
FROM bonds b;
