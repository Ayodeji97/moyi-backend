-- Slice C6b: full-text search over entries (FR-050, FR-091, ADR-0039).
--
-- What is searched is entries.text_search (V12), not entries.text. Doc 07 §2 keeps the two
-- apart for Phase 5 (D-13): when text becomes ciphertext, text_search becomes a lossy
-- projection and search keeps working against it, "an acknowledged partial disclosure"
-- (doc 09). Until then the projection is the text itself, written by the application
-- (`searchProjectionOf`, in EntryStore) on submit and on an edit before the reveal, and
-- emptied by every erasure, which goes through EntryStore.update with the words gone
-- (ADR-0039 decision 5). It is the one copy of an entry's words outside entries.text that
-- this codebase keeps (CLAUDE.md, amended with this slice).
--
-- search_vector is Postgres's, generated from the projection and never written by anybody.
-- 'simple', not 'english': entries are multilingual, and 'simple' neither stems nor drops
-- stopwords, so a search is for whole words as written (the owner's Q-S4). Accents and case
-- fold: "cafe" finds "Café".
--
-- unaccent is STABLE, in both its forms, and a generated column takes only IMMUTABLE
-- expressions. immutable_unaccent is the usual wrapper: the dictionary named, the schema
-- named, and a SQL-standard body, parsed now, so its dependency on the extension is
-- recorded. The promise of immutability is ours: if an upgrade of Postgres changes
-- unaccent.rules, stored vectors keep the old folding until they are regenerated
-- (UPDATE entries SET text_search = text_search). The search's query folds its words with
-- this same function, so the two sides cannot fold differently.
--
-- The backfill fills the projection of every live row before the column is added. That is
-- two passes over entries, not one: the UPDATE writes a new version of every live row, then
-- ADD COLUMN ... STORED rewrites the whole table under ACCESS EXCLUSIVE, and the index is
-- built under the same lock. Measured on 190k live rows (Task 1 review, 2026-10-21): 2.7 s,
-- 5.5 s and 0.4 s; entries is unreadable for about 6 s per 200k rows, and grows 136 -> 362 MB.
-- An erased row keeps NULL: its words are gone (BR-10). Both marks of an erasure are asked,
-- as Entry.isErased asks them.
--
-- lock_timeout: the rewrite must not queue every request behind a long transaction (the
-- withdrawal consumer holds one for up to 60 s). If the lock is not granted in 5 s the
-- migration fails, rolls back whole, and the deploy is retried; it never waits with the
-- table's traffic queued behind it. SET LOCAL ends with Flyway's transaction.
--
-- entries_search_idx: GIN on (bond_id, search_vector), through btree_gin. Measured on this
-- branch (SearchPlanMeasurement, 2026-10-21; the numbers are in ADR-0039): with GIN on the
-- vector alone, a search for a common word in one bond read that word's posting list for
-- the whole table, 62,509 rows of 500,000 for a bond of 1,460 entries, and ANDed it with
-- the bond's btree scan, so its cost grew with the table (NFR-033). GIN keeps one posting
-- list per key across the whole table; with bond_id as a second key of the same index, the
-- intersection is made inside the index, and Postgres can skip through the word's posting
-- tree using the bond's. bond_id is a uuid, which plain GIN cannot index: btree_gin gives it
-- the operator class.
SET LOCAL lock_timeout = '5s';

CREATE EXTENSION IF NOT EXISTS unaccent SCHEMA public;
CREATE EXTENSION IF NOT EXISTS btree_gin SCHEMA public;

CREATE FUNCTION public.immutable_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN public.unaccent('public.unaccent'::regdictionary, $1);

UPDATE entries
SET text_search = text
WHERE text IS NOT NULL AND deleted_at IS NULL AND status <> 'DELETED';

ALTER TABLE entries
    ADD COLUMN search_vector tsvector
        GENERATED ALWAYS AS (to_tsvector('simple', public.immutable_unaccent(coalesce(text_search, '')))) STORED;

CREATE INDEX entries_search_idx ON entries USING gin (bond_id, search_vector);
