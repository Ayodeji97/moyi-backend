-- Issue #141: the run the latest break ended, for "your streak ended at 21 days"
-- (states.md §7). `longest_streak` is the longest run ever, not the last one.
--
-- Part of the projection (ADR-0034, as amended 2026-11-08): `StreakRules.step`
-- sets both on a day that breaks a run and on no other, and `recalculate`
-- rebuilds them from the days like every other column here.
--   last_run_length    the run's length, at least one: a missed day on a run
--                      of zero breaks nothing. Null until a run has ended.
--   last_run_broken_on the date of the missed day that ended it, itself not in
--                      the run. Null exactly when last_run_length is.
ALTER TABLE streak_states
    ADD COLUMN last_run_length    integer,
    ADD COLUMN last_run_broken_on date,
    ADD CONSTRAINT streak_states_last_run_check CHECK (
        (last_run_length IS NULL AND last_run_broken_on IS NULL)
        OR (last_run_length >= 1 AND last_run_length <= longest_streak AND last_run_broken_on IS NOT NULL)
    );

-- Every break so far is in the audit log, written in the evaluation's
-- transaction (V17): `streak_before` of the latest BROKEN line is the run it
-- ended. `recalculate` replays the same recorded decisions, so it would set
-- the same values; this spares running it over every bond.
UPDATE streak_states s
SET last_run_length = b.streak_before, last_run_broken_on = b.date
FROM (
    SELECT DISTINCT ON (bond_id) bond_id, date, streak_before
    FROM streak_events
    WHERE event = 'BROKEN'
    ORDER BY bond_id, date DESC
) b
WHERE s.bond_id = b.bond_id;
