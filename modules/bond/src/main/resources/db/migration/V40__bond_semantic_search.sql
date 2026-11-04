-- V40: whether a bond's entries are embedded for semantic search (FR-204, ADR-0054).
-- On by default (the owner's ruling, 2026-10-30); either member turns it off, both turn it on.
ALTER TABLE bonds ADD COLUMN semantic_search_enabled boolean NOT NULL DEFAULT true;

ALTER TABLE bond_proposals DROP CONSTRAINT bond_proposals_kind_check;
ALTER TABLE bond_proposals ADD CONSTRAINT bond_proposals_kind_check
    CHECK (kind IN ('TIMEZONE_CHANGE', 'DELETION', 'SEMANTIC_SEARCH_ON'));
