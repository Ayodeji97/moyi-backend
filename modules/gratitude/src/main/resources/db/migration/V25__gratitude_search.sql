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
-- The backfill fills the projection of every live row, before the column is added, so the
-- table is rewritten once. An erased row keeps NULL: its words are gone (BR-10). Both marks
-- of an erasure are asked, as Entry.isErased asks them.
--
-- entries_search_idx: GIN on the vector. Whether a query on one bond's entries reads in
-- proportion to that bond or to the table (NFR-033) was measured on this branch; ADR-0039
-- has the numbers and says whether this became btree_gin (bond_id, search_vector).
CREATE EXTENSION IF NOT EXISTS unaccent SCHEMA public;

CREATE FUNCTION public.immutable_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN public.unaccent('public.unaccent'::regdictionary, $1);

UPDATE entries
SET text_search = text
WHERE text IS NOT NULL AND deleted_at IS NULL AND status <> 'DELETED';

ALTER TABLE entries
    ADD COLUMN search_vector tsvector
        GENERATED ALWAYS AS (to_tsvector('simple', public.immutable_unaccent(coalesce(text_search, '')))) STORED;

CREATE INDEX entries_search_idx ON entries USING gin (search_vector);
