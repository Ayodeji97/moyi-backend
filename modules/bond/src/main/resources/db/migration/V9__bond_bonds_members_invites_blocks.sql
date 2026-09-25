-- Bond — doc 07 §2 "bond", ADR-0003 (a generic Bond, not a Couple), ADR-0026.
--
-- Owned by `modules/bond` (ADR-0014): the module's own test context builds
-- this schema by itself, which it has to, because the entities under test are
-- `internal` and unreachable from `app`. Flyway merges every
-- `classpath:db/migration` on the classpath, so `app` picks this up through
-- its dependency on the module.
--
-- Versions are one global sequence across modules, because they are one
-- schema: V1 is `app`'s extensions, V2–V8 are identity's, this is the first
-- of bond's. Forward-only (doc 07 §1) — a mistake here is corrected by V10,
-- never by editing this file.
--
-- Three deltas from doc 07 §2, each recorded in ADR-0026: `nickname_for_other`
-- (ADR-0003's `partner` → `other` rename), `created_by_member_id` (doc 04's
-- own name for it; doc 07 said `created_by`), and `version`, which is the
-- ETag `If-Match` compares against (doc 06 §1). **No foreign key points at
-- `users`**: a user id crosses the module boundary as a bare uuid (doc 25 §6),
-- and referential integrity between modules is the application's job.

CREATE TABLE bonds (
    id                    uuid        PRIMARY KEY,
    -- Text with a CHECK rather than a Postgres enum, like every other status
    -- column in this schema (see V2 for the reasoning). FR-030 asks that a new
    -- Bond type need no schema migration; with this shape it is a widened
    -- CHECK and no data movement at all.
    type                  text        NOT NULL DEFAULT 'COUPLE',
    name                  text        NOT NULL,
    -- An IANA *region* id such as Africa/Lagos, validated in the domain
    -- (`RegionZone`). Never an offset: offsets change twice a year, zones do
    -- not, and doc 04 §6 makes storing the zone id the rule.
    anchor_timezone       text        NOT NULL,
    -- When the anchor last moved, for FR-027's once-per-30-days rule (B5).
    timezone_changed_at   timestamptz,
    reveal_time_local     time,
    strict_mode           boolean     NOT NULL DEFAULT false,
    status                text        NOT NULL,
    -- On the row rather than in a constant: this is what makes FR-021's
    -- "a configurable property of the Bond type" real rather than aspirational
    -- (doc 07 §2). Two for every type in v1.
    max_members           smallint    NOT NULL DEFAULT 2,
    created_by            uuid        NOT NULL,
    created_at            timestamptz NOT NULL,
    archived_at           timestamptz,
    deletion_requested_at timestamptz,
    -- Optimistic concurrency for PATCH /bonds/{id} (B4): Hibernate's @Version
    -- adds `WHERE version = ?` to every UPDATE, which is what makes `If-Match`
    -- a real check under concurrency rather than one the application performs
    -- and then forgets to honour.
    version               integer     NOT NULL DEFAULT 0,

    CONSTRAINT bonds_type_check CHECK (type IN ('COUPLE', 'FRIENDS', 'FAMILY', 'PARENT_CHILD')),
    CONSTRAINT bonds_status_check CHECK (status IN ('PENDING_MEMBER', 'ACTIVE', 'ARCHIVED', 'PENDING_DELETION', 'DELETED')),
    -- 60 is a number chosen in the Phase 2 design (§5.2), not one FR-020
    -- gives — flagged for Daniel exactly as the 80-character display name was.
    CONSTRAINT bonds_name_length_check CHECK (char_length(name) BETWEEN 1 AND 60),
    -- A bound, not validation: the shape of a zone id is the domain's job.
    -- The longest id in the tzdb is 32 characters; 64 is room to spare.
    CONSTRAINT bonds_anchor_timezone_length_check CHECK (char_length(anchor_timezone) BETWEEN 1 AND 64),
    CONSTRAINT bonds_max_members_check CHECK (max_members >= 2)
);

CREATE TABLE bond_members (
    id                  uuid        PRIMARY KEY,
    bond_id             uuid        NOT NULL REFERENCES bonds (id) ON DELETE CASCADE,
    user_id             uuid        NOT NULL,
    role                text        NOT NULL,
    joined_at           timestamptz NOT NULL,
    -- Set when the member leaves (FR-026). The row stays: each member keeps
    -- read access to the archive afterwards (states.md §9, OQ-09), so
    -- "is this person a member of that bond" must stay answerable for them.
    left_at             timestamptz,
    reminder_time_local time        NOT NULL DEFAULT '20:00',
    -- The MEMBER's own zone, deliberately independent of the bond's anchor.
    -- Doc 04 §6 calls this distinction the single most misunderstood thing in
    -- the system, so the column comment says it too.
    reminder_timezone   text        NOT NULL,
    quiet_hours_start   time,
    quiet_hours_end     time,
    -- Unmapped by any entity until Phase 4's notifications; the default fills
    -- it so a row written today is still valid then.
    notification_prefs  jsonb       NOT NULL DEFAULT '{}',
    nickname_for_other  text,

    CONSTRAINT bond_members_role_check CHECK (role IN ('OWNER', 'MEMBER')),
    CONSTRAINT bond_members_reminder_timezone_length_check CHECK (char_length(reminder_timezone) BETWEEN 1 AND 64),
    CONSTRAINT bond_members_nickname_length_check
        CHECK (nickname_for_other IS NULL OR char_length(nickname_for_other) BETWEEN 1 AND 40)
);

-- One ACTIVE membership per user per bond — the half of I-1 that says the same
-- person cannot hold two seats. Partial, so a member who left and was invited
-- back gets a second row rather than a constraint violation.
CREATE UNIQUE INDEX bond_members_active_key ON bond_members (bond_id, user_id) WHERE left_at IS NULL;

-- "My bonds", read on every app open (doc 07 §4).
CREATE INDEX bond_members_user_id_active_idx ON bond_members (user_id) WHERE left_at IS NULL;

-- Loading a bond with its members, and the access guard's membership lookup —
-- the query on the front of every bond-scoped request.
CREATE INDEX bond_members_bond_id_idx ON bond_members (bond_id);

CREATE TABLE bond_invites (
    id                   uuid        PRIMARY KEY,
    bond_id              uuid        NOT NULL REFERENCES bonds (id) ON DELETE CASCADE,
    -- Six characters of the 30-symbol alphabet states.md §2 fixed on
    -- 2026-09-03. The CHECK *is* the alphabet, so a code outside it cannot be
    -- stored by any path — the last line of defence for T-06 after the domain
    -- type and the rate limits.
    code                 text        NOT NULL,
    created_by_member_id uuid        NOT NULL REFERENCES bond_members (id) ON DELETE CASCADE,
    expires_at           timestamptz NOT NULL,
    used_at              timestamptz,
    used_by_user_id      uuid,
    revoked_at           timestamptz,

    -- Unique across every invite ever issued, live or spent. The code space is
    -- 7.3e8 and this table stays small, so a collision is a failed insert for
    -- one request and a fresh code for the next — not a reason to scope
    -- uniqueness to live rows and risk two bonds answering one code.
    CONSTRAINT bond_invites_code_key UNIQUE (code),
    CONSTRAINT bond_invites_code_shape_check CHECK (code ~ '^[23456789ABCDEFGHJKMNPQRSTVWXYZ]{6}$')
);

-- Resolving and accepting a code (B2) cares only about live invites, and the
-- partial index keeps it small as spent ones accumulate.
CREATE INDEX bond_invites_live_code_idx ON bond_invites (code) WHERE used_at IS NULL AND revoked_at IS NULL;
CREATE INDEX bond_invites_bond_id_idx ON bond_invites (bond_id);

-- FR-029. Written by slice B3's block, and read by B2's accept in **both**
-- directions: a block prevents any future invitation between two accounts,
-- whichever of them holds the code.
CREATE TABLE blocks (
    id              uuid        PRIMARY KEY,
    blocker_user_id uuid        NOT NULL,
    blocked_user_id uuid        NOT NULL,
    bond_id         uuid        NOT NULL REFERENCES bonds (id) ON DELETE CASCADE,
    created_at      timestamptz NOT NULL,

    -- FR-025 permits three bonds and nothing stops the same two people sharing
    -- two of them (doc 07 §2), so the bond is part of the key rather than the
    -- pair alone.
    CONSTRAINT blocks_pair_bond_key UNIQUE (blocker_user_id, blocked_user_id, bond_id),
    CONSTRAINT blocks_not_self_check CHECK (blocker_user_id <> blocked_user_id)
);

CREATE INDEX blocks_blocked_user_id_idx ON blocks (blocked_user_id);
CREATE INDEX blocks_blocker_user_id_idx ON blocks (blocker_user_id);
