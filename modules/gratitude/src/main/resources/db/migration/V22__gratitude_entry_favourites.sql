-- Slice C5b: a member's private bookmark on an entry (FR-093, doc 04
-- `EntryFavourite`, spec §6.6).
--
-- A row per member and entry, and not a flag on `entries`, on purpose: an
-- entry is shared, so any column on it is readable by both people, and the
-- other person's bookmark is exactly what must appear in no response (FR-064:
-- it would be a read receipt). Keyed this way there is no query that returns
-- "is this entry a favourite" without naming whose.
--
-- member_id is the caller's bond_members id IN THE ENTRY'S BOND, as
-- entries.author_member_id is: never a user id. One person in two bonds is
-- two members, and a bookmark belongs to the one who can read the entry. It
-- carries no foreign key, on purpose: bond_members is another module's table
-- and no reference crosses a module boundary (V12 says the same of
-- entries.author_member_id and entries.bond_id). Nothing removes a member
-- row today; if something ever does, its bookmarks are that change's to
-- remove.
--
-- entry_id does carry one: entries is this module's own. ON DELETE CASCADE
-- is for the day an entry's ROW goes, which today only happens when its
-- bond-day does (bond_days ON DELETE CASCADE from V12). It is NOT what
-- removes a bookmark when an entry is deleted or withdrawn: an erasure keeps
-- the row as a tombstone, so no cascade ever fires. `EraseEntry` deletes the
-- bookmarks itself, in the erasure's own transaction (ADR-0035, "Owed, C5b").
--
-- created_at is when the member marked it, cut to microseconds by the writer.
CREATE TABLE entry_favourites (
    entry_id   uuid        NOT NULL REFERENCES entries (id) ON DELETE CASCADE,
    member_id  uuid        NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (entry_id, member_id)
);

-- "Which of these entries has this member marked", and the archive's
-- favourites filter: both start from the member. The primary key starts
-- from the entry and serves the erasure.
CREATE INDEX entry_favourites_by_member_idx ON entry_favourites (member_id, entry_id);
