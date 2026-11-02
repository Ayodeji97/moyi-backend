-- V41: an entry's text_search as vectors, one table per embedding model (FR-204, ADR-0054).
-- No approximate index: a bond's rows are few, so an exact scan through bond_id is faster and misses nothing.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE entry_embeddings_openai_1536 (
    entry_id   uuid PRIMARY KEY REFERENCES entries (id) ON DELETE CASCADE,
    bond_id    uuid NOT NULL,
    embedding  vector(1536) NOT NULL,
    text_hash  bytea NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX entry_embeddings_openai_1536_bond_idx ON entry_embeddings_openai_1536 (bond_id);

CREATE TABLE entry_embeddings_voyage_1024 (
    entry_id   uuid PRIMARY KEY REFERENCES entries (id) ON DELETE CASCADE,
    bond_id    uuid NOT NULL,
    embedding  vector(1024) NOT NULL,
    text_hash  bytea NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX entry_embeddings_voyage_1024_bond_idx ON entry_embeddings_voyage_1024 (bond_id);
