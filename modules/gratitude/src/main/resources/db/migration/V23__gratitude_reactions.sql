-- Slice C5c: one small acknowledgement a member leaves on their partner's
-- revealed entry (FR-048, doc 04 `Reaction`).
--
-- A row per entry and member, keyed by the pair: one reaction per member per
-- entry (the owner's ruling of 2026-10-06). Doc 07 keys it by (entry_id,
-- member_id, type), which would let one member leave every type at once;
-- here setting another type replaces the first.
--
-- member_id is the reactor's bond_members id IN THE ENTRY'S BOND, as
-- entries.author_member_id is: never a user id. It carries no foreign key,
-- on purpose: bond_members is another module's table and no reference
-- crosses a module boundary (V12 and V22 say the same of their member
-- columns). It is never the entry's author: only the member who did not
-- write an entry may react to it, and the one statement that writes a row
-- here refuses the author itself (`Reactions.set`). Nothing removes a member
-- row today; if something ever does, its reactions are that change's to
-- remove.
--
-- entry_id does carry one: entries is this module's own. ON DELETE CASCADE
-- is for the day an entry's ROW goes, which today only happens when its
-- bond-day does. It is NOT what removes a reaction when an entry is deleted
-- or withdrawn: an erasure keeps the row as a tombstone, so no cascade ever
-- fires. `EraseEntry` deletes the reactions itself, in the erasure's own
-- transaction, as it deletes the bookmarks (V22).
--
-- There is NO time column, on purpose. Doc 07 gives the row `created_at`.
-- A reaction can only be made by somebody reading the entry, so the time of
-- a reaction is the time the partner read it: the read receipt FR-064
-- forbids, kept in a table for no purpose, since nothing orders or filters
-- by it and no response may carry it. V22 kept `entry_favourites.created_at`
-- because the corpus specifies it; this table keeps less.
--
-- The CHECK holds the placeholder set of four ("placeholder" is the owner's
-- word). Adding or renaming a type is a new migration and a breaking change
-- to the contract's enum.
--
-- No second index: every question starts from the entry (the reaction on
-- each of these entries; every reaction on this entry, for the erasure), and
-- the primary key starts there.
CREATE TABLE reactions (
    entry_id  uuid NOT NULL REFERENCES entries (id) ON DELETE CASCADE,
    member_id uuid NOT NULL,
    type      text NOT NULL,
    PRIMARY KEY (entry_id, member_id),

    -- Named, as every constraint in the schema is: the migration that changes the set drops it by this name.
    CONSTRAINT reactions_type_check CHECK (type IN ('HEART', 'THANK_YOU', 'SMILE', 'MOVED'))
);
