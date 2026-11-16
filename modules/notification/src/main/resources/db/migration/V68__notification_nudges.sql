-- Issue #200 (ADR-0068). Which nudge of its Bond-day a REMINDER is; null for every other push and
-- for every row written before this migration (the payload then leaves `nudge` out). No upper bound
-- here: the count is bond.api's (NUDGES_PER_BOND_DAY), and a second copy would drift.
ALTER TABLE notifications ADD COLUMN nudge smallint;
ALTER TABLE notifications ADD CONSTRAINT notifications_nudge_check
    CHECK (nudge IS NULL OR (nudge >= 1 AND category = 'REMINDER'));

-- FR-082 as amended: how many of the member's cross-Bond slots this push must leave free when it is
-- sent (ruling 7). Zero for everything but nudges 2 and 3. Kept on the row because a held push is
-- decided again from its row alone (V58's reason).
ALTER TABLE notifications ADD COLUMN leave_free_across_bonds smallint NOT NULL DEFAULT 0;
ALTER TABLE notifications ADD CONSTRAINT notifications_leave_free_check
    CHECK (leave_free_across_bonds BETWEEN 0 AND 5);
