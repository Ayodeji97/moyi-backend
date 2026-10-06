-- Slice C4 (ADR-0034 decision 6). Every change of a bond's Strict mode, and
-- when, so that the streak can judge a day by the setting it ended under
-- (FR-073: "toggling Strict mode never alters past days").
--
-- The streak is worked out after a day has ended, and either member can
-- change the setting alone in the meantime. Judged by the setting in force
-- when the job ran, a day missed in Strict mode could be rescued by switching
-- it off a moment later. One "last changed at" column was tried first and is
-- not enough: off and on again, both after the day ended, and the last change
-- says the day was not strict. So it is a history.
--
-- A row is a real change: `strict_mode` is the value from `changed_at` on, and
-- the value before a bond's first row is the opposite of that row's. A bond
-- with no row has never changed it.
CREATE TABLE bond_strict_mode_changes (
    bond_id     uuid        NOT NULL REFERENCES bonds (id) ON DELETE CASCADE,
    changed_at  timestamptz NOT NULL,
    strict_mode boolean     NOT NULL,

    -- Also the read: one bond's changes, in order.
    PRIMARY KEY (bond_id, changed_at)
);
