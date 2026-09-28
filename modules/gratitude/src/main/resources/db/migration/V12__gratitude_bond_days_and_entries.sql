-- Bond-day and entry — doc 07 §2 "gratitude", with three deltas from that
-- DDL, each recorded in the Phase 3 design §12.
--
-- Owned by `modules/gratitude` (ADR-0014, as V9 is bond's): the module's own
-- test context builds this schema by itself, because the entities under test
-- are `internal` and unreachable from `app`. Flyway merges every
-- `classpath:db/migration` on the classpath, so `app` picks this up through
-- its dependency on the module.
--
-- Versions are one global sequence across modules: V1 is `app`'s extensions,
-- V2-V8 are identity's, V9 is bond's, V11 is common:web's idempotency table
-- (V10 is reserved elsewhere and simply is not on this worktree's classpath
-- yet — Flyway tolerates the gap). Forward-only (doc 07 §1) — a mistake here
-- is corrected by a later version, never by editing this file.
--
-- Three deltas from doc 07 §2's own DDL, each recorded in the Phase 3
-- design §12:
--
--   * `status` carries all EIGHT of doc 04 §3's values. Doc 07's own DDL
--     lists five and is stale: it is missing PENDING_REVEAL (FR-062, the
--     window between both entries arriving and the reveal job running),
--     SUSPENDED (doc 04 §8.1-8.3a, a PENDING_MEMBER bond's own day) and
--     FROZEN (BR-5, doc 04 §8.5, a streak-preserving freeze token).
--   * `bond_days.anchor_timezone` is new. BR-6 and ADR-0030 require an
--     anchor change never to recompute an existing day; the row carries the
--     zone it was opened in so that is structural rather than remembered.
--   * `entries.text`'s bound is an octet cap, not doc 07's `char_length(text)
--     <= 4000`. FR-041 names that number as its own first draft's error:
--     4,000 code points allows 8 per grapheme and one ZWJ family emoji is
--     10. Only `octet_length(text) <= 8192` is enforced below — a *second*
--     `char_length(text) <= 8192` check was deliberately not added: UTF-8
--     gives at least one byte per code point, so the octet bound already
--     implies no more than 8192 code points either. Stating both would not
--     have been a second limit, only the same one written twice.
--
-- No foreign key leaves this module: `bond_id` and `author_member_id` are
-- ids that bond owns, and referential integrity across a module boundary is
-- the application's job (doc 25 §6, ADR-0026). The one FK that stays —
-- `entries.bond_day_id` to `bond_days.id` — crosses no boundary: both
-- tables are this module's own.
CREATE TABLE bond_days (
    id              uuid        PRIMARY KEY,
    bond_id         uuid        NOT NULL,
    date            date        NOT NULL,
    status          text        NOT NULL,
    -- The IANA zone id this day was opened under — never re-read from the
    -- bond's current anchor. See the class delta above and BondDay's own
    -- KDoc: BR-6/ADR-0030 forbid an anchor move from recomputing this row.
    anchor_timezone text        NOT NULL,
    entry_count     smallint    NOT NULL DEFAULT 0,
    revealed_at     timestamptz,
    closed_at       timestamptz,
    created_at      timestamptz NOT NULL,
    -- The row version behind the `ETag` (doc 06 §1), as `bonds.version` is.
    -- Unused by this slice's own writes — C1 has no reveal, no close — but
    -- present from the first row so a later slice's `@Version` needs no
    -- migration of its own.
    version         integer     NOT NULL DEFAULT 0,

    CONSTRAINT bond_days_status_check CHECK (
        status IN ('OPEN', 'PARTIAL', 'PENDING_REVEAL', 'REVEALED', 'SOLO', 'EMPTY', 'SUSPENDED', 'FROZEN')
    ),
    CONSTRAINT bond_days_entry_count_check CHECK (entry_count BETWEEN 0 AND 2),
    CONSTRAINT bond_days_timezone_length_check CHECK (char_length(anchor_timezone) BETWEEN 1 AND 64)
);

-- The constraint the whole phase rests on: one row per bond per date. Also
-- what `openOrGet`'s `INSERT ... ON CONFLICT (bond_id, date) DO NOTHING`
-- targets — the lazy open is a compare-and-set against this index, not a
-- check-then-insert (ADR-0027's shape, B2's own invite-creation precedent).
CREATE UNIQUE INDEX bond_days_bond_date_key ON bond_days (bond_id, date);
-- The archive feed, which is the hottest read in the product (doc 07 §3).
CREATE INDEX bond_days_feed_idx ON bond_days (bond_id, date DESC);
-- The close job's scan (C3), partial so it stays small.
CREATE INDEX bond_days_open_idx ON bond_days (status, date) WHERE status IN ('OPEN', 'PARTIAL', 'PENDING_REVEAL');

CREATE TABLE entries (
    id                     uuid        PRIMARY KEY,
    bond_day_id            uuid        NOT NULL REFERENCES bond_days (id) ON DELETE CASCADE,
    -- Bond's own id, carried flat rather than reached through bond_day_id's
    -- join — every query this module runs against `entries` filters by bond
    -- directly (doc 07 §2), and bond owns the id so no FK follows it.
    bond_id                uuid        NOT NULL,
    -- The member who wrote it. Bond's id too, and for the same reason.
    author_member_id       uuid        NOT NULL,
    -- Nullable in the schema for the media-only entry a later Phase 4 slice
    -- adds; this slice's domain (`Entry.text: EntryText`, not `EntryText?`)
    -- never produces a NULL here. See `Entry`'s own KDoc.
    text                   text,
    -- Postgres's own derived search artifact (C6's `tsvector`). No entity in
    -- this task maps it; it has nothing to be written from yet.
    text_search            text,
    image_media_id         uuid,
    voice_media_id         uuid,
    voice_duration_ms      integer,
    prompt_id              uuid,
    status                 text        NOT NULL,
    author_deleted_account boolean     NOT NULL DEFAULT false,
    created_at             timestamptz NOT NULL,
    -- BR-3/BR-3a's resolved day, distinct from created_at when an offline
    -- draft back-files (DayAssignment).
    intended_at            timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    revealed_at            timestamptz,
    deleted_at             timestamptz,

    CONSTRAINT entries_status_check CHECK (status IN ('SUBMITTED', 'REVEALED', 'DELETED')),
    -- FR-041's octet backstop. No accompanying char_length check: see the
    -- header comment above for why a second statement of the same 8192
    -- bound would not have been a second limit.
    CONSTRAINT entries_text_octets_check CHECK (text IS NULL OR octet_length(text) <= 8192)
);

-- BR-2, and BR-2 says "enforced by a unique index, not by application logic
-- alone". Partial, so a deleted entry does not hold the slot: an author who
-- deletes before reveal may write again that day.
CREATE UNIQUE INDEX entries_one_per_member_per_day ON entries (bond_day_id, author_member_id) WHERE deleted_at IS NULL;
CREATE INDEX entries_bond_recent_idx ON entries (bond_id, created_at DESC);
