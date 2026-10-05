-- Slice C4. When a bond's Strict mode last changed, so that the streak can
-- judge a day by the setting it ended under (FR-073: "toggling Strict mode
-- never alters past days"). The streak is worked out after a day has ended,
-- and either member can change the setting alone in the meantime: without
-- this, switching Strict mode off a moment after a missed day had a freeze
-- spent on it. Null for a bond that has never changed it.
ALTER TABLE bonds ADD COLUMN strict_mode_changed_at timestamptz;
