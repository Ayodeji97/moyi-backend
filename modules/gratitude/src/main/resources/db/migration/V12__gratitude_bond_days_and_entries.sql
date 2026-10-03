-- Bond-day and entry — doc 07 §2 "gratitude".
--
-- Owned by `modules/gratitude` (ADR-0014, as V9 is bond's): the module's own
-- test context builds this schema by itself, because the entities under test
-- are `internal` and unreachable from `app`. Flyway merges every
-- `classpath:db/migration` on the classpath, so `app` picks this up through
-- its dependency on the module.
--
-- Versions are one global sequence across modules: V1 is `app`'s extensions,
-- V2-V8 are identity's, V9-V10 are bond's (V10 is `bond_proposals`, B5),
-- V11 is common:web's idempotency table, this is V12, and V13 is bond's
-- `bond_anchor_intervals`. Forward-only (doc 07 §1) once merged — this file
-- is still unmerged on `feat/gratitude-day`, which is the one exception that
-- lets the C1 rework edit it in place rather than stack a corrective version.
--
-- (An earlier header carried F11's merge-sequencing warning about a V10 that
-- was reserved elsewhere but absent from this worktree's classpath. Both
-- halves are discharged: V10 arrived with B5, and `main` was merged into this
-- branch at `ed0e9f2`, so the versions this branch applies are the versions
-- `main` has.)
--
-- Four deltas from doc 07 §2's own DDL, each recorded in the Phase 3
-- design §12 or the C1 rework plan:
--
--   * `status` carries all EIGHT of doc 04 §3's values. Doc 07's own DDL
--     lists five and is stale: it is missing PENDING_REVEAL (FR-062, the
--     window between both entries arriving and the reveal job running),
--     SUSPENDED (doc 04 §8.1-8.3a, a PENDING_MEMBER bond's own day) and
--     FROZEN (BR-5, doc 04 §8.5, a streak-preserving freeze token).
--   * `bond_days.anchor_timezone` is new. BR-6 and ADR-0030 require an
--     anchor change never to recompute an existing day. It is a *snapshot*
--     of the zone in force when the day began, kept for display and audit —
--     it is NOT what decides which instants belong to the day.
--   * `bond_days.starts_at`/`ends_at` are new (spec §3.1), and they are that
--     authority: the UTC span the bond's anchor timeline gives this day —
--     `starts_at` fixed when the row was opened, `ends_at` extendable while
--     the day is unsettled (see the column comment). A zone id alone cannot express a day an anchor
--     change merged (westward, plan R3: 25h for a one-hour move, 49h for
--     Kiritimati to Pago_Pago) or skipped (empty, eastward).
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
    -- The IANA zone id in force when this day began — a snapshot, never
    -- re-read from the bond's current anchor, and never the authority on
    -- the day's span: `starts_at`/`ends_at` are. See the header and BondDay's
    -- own KDoc: BR-6/ADR-0030 forbid an anchor move from recomputing this row.
    anchor_timezone text        NOT NULL,
    -- The UTC span this day occupies, `[starts_at, ends_at)`, taken from the
    -- bond's effective-zone timeline. `starts_at` is fixed when the row is
    -- opened and never moves. `ends_at` is what the timeline said then, and
    -- may be EXTENDED afterwards — only later, only while the day is
    -- unsettled — when a westward anchor change merges the day into its
    -- successor's start (ADR-0031, ruling P10; `BondDay.extendedTo`). It is
    -- never shortened, and a settled day's span never changes.
    -- A zone id alone cannot express a day that a mid-day anchor change
    -- clipped, nor a calendar label an eastward change skipped entirely —
    -- the latter is a day whose span is EMPTY (starts_at = ends_at), which
    -- is why the CHECK below is `>=` and not `>`.
    starts_at       timestamptz NOT NULL,
    ends_at         timestamptz NOT NULL,
    entry_count     smallint    NOT NULL DEFAULT 0,
    revealed_at     timestamptz,
    closed_at       timestamptz,
    created_at      timestamptz NOT NULL,
    -- The row version behind the `ETag` (doc 06 §1), as `bonds.version` is.
    -- Hibernate's `@Version`: `days.update` bumps it on every change this
    -- slice makes to a row — an entry arriving, an extended `ends_at`. No
    -- endpoint exposes it as an `ETag` yet; that is a later slice's.
    version         integer     NOT NULL DEFAULT 0,

    CONSTRAINT bond_days_status_check CHECK (
        status IN ('OPEN', 'PARTIAL', 'PENDING_REVEAL', 'REVEALED', 'SOLO', 'EMPTY', 'SUSPENDED', 'FROZEN')
    ),
    CONSTRAINT bond_days_entry_count_check CHECK (entry_count BETWEEN 0 AND 2),
    CONSTRAINT bond_days_timezone_length_check CHECK (char_length(anchor_timezone) BETWEEN 1 AND 64),
    CONSTRAINT bond_days_span_check CHECK (ends_at >= starts_at)
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
    -- Nullable for a tombstone (BR-10: text nulled, status DELETED, row kept)
    -- and for the media-only entry a later Phase 4 slice adds. This slice
    -- never WRITES a NULL here, but it must already READ one: an
    -- Idempotency-Key replay re-reads the entry. See `Entry`'s own KDoc.
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
    -- draft back-files (DayAssignment.resolve). Always the instant the day
    -- assignment actually accepted, never the raw client claim (F1,
    -- whole-branch review): when the caller's intendedAt fails BR-3a's
    -- ahead-of-now/offline-window/settled-day checks, this column holds
    -- submittedAt, the same as if none had been sent — an out-of-window
    -- claim is never persisted here.
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
-- Every entry of one day, tombstones included: `findAllByBondDayId`, on every
-- `GET /today`. The unique index above cannot serve it — its predicate is
-- `deleted_at IS NULL`, and this read has none — so without this the read is
-- a scan of the whole table. Plain, and beside the partial index, not instead
-- of it: that one is BR-2, this one is a lookup. Added while V12 was still
-- unmerged (final whole-branch review, A7).
CREATE INDEX entries_bond_day_idx ON entries (bond_day_id);
CREATE INDEX entries_bond_recent_idx ON entries (bond_id, created_at DESC);
