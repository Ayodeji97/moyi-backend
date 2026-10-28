-- The fifth reaction (ADR-0037 question 1, the owner's ruling of 2026-10-21):
-- HEART, THANK_YOU, SMILE, MOVED and ME_TOO ("I'm grateful for that too").
-- How each is drawn is the design's.
--
-- V23's CHECK held the placeholder set of four. It is dropped and put back
-- with the five, under the same name, `reactions_type_check`, which V23 gave
-- it so that this migration could drop it by name (`FlywayMigrationTest`
-- reads the name back from the catalogue). ME_TOO is last, in the ruling's
-- order, as it is in `ReactionType` and the contract's enum; nothing else is
-- renamed or reordered, and no row changes: every row already holds one of
-- the four, which the new CHECK allows.
--
-- One statement, so the table is never without its CHECK. Re-adding it
-- checks every existing row under the table's lock; the table holds one row
-- per reacting member per entry, so that is brief.
--
-- Once a ME_TOO row exists, an image older than this migration cannot read
-- that bond's reactions: its enum has no ME_TOO. A rollback past here after
-- anyone has reacted ME_TOO maps or removes those rows first (ADR-0037, its
-- amendment of 2026-10-28, and question 13).
ALTER TABLE reactions
    DROP CONSTRAINT reactions_type_check,
    ADD CONSTRAINT reactions_type_check CHECK (type IN ('HEART', 'THANK_YOU', 'SMILE', 'MOVED', 'ME_TOO'));
