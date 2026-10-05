-- Slice C4: the streak (spec §3.3, §6.5; doc 07 §2).

-- One row per bond: the run through the last of its days that has been
-- evaluated. A projection — `recalculate` (FR-074) rebuilds it from the
-- bond's days and what was decided for each (the three columns added to
-- `bond_days` below). No foreign key to `bonds`: it is another module's table.
CREATE TABLE streak_states (
    bond_id             uuid        PRIMARY KEY,
    current_streak      integer     NOT NULL DEFAULT 0,
    longest_streak      integer     NOT NULL DEFAULT 0,
    last_complete_date  date,
    freezes_available   smallint    NOT NULL DEFAULT 0,
    freeze_progress     smallint    NOT NULL DEFAULT 0,
    freezes_consumed    integer     NOT NULL DEFAULT 0,
    total_complete_days integer     NOT NULL DEFAULT 0,
    -- The last time `recalculate` rebuilt this row; null if it never has.
    recomputed_at       timestamptz,
    updated_at          timestamptz NOT NULL,

    CONSTRAINT streak_states_run_check CHECK (current_streak >= 0 AND longest_streak >= current_streak),
    -- BR-5: at most two banked; progress resets at the fourteenth complete day.
    CONSTRAINT streak_states_freezes_check CHECK (freezes_available BETWEEN 0 AND 2),
    CONSTRAINT streak_states_progress_check CHECK (freeze_progress BETWEEN 0 AND 13),
    CONSTRAINT streak_states_counts_check CHECK (freezes_consumed >= 0 AND total_complete_days >= 0)
);

-- Append-only: what each evaluated day did to the run, so that "why did my
-- streak break?" has an answer that was written down at the time (doc 07 §2).
CREATE TABLE streak_events (
    id            uuid        PRIMARY KEY,
    bond_id       uuid        NOT NULL,
    date          date        NOT NULL,
    event         text        NOT NULL,
    streak_before integer     NOT NULL,
    streak_after  integer     NOT NULL,
    created_at    timestamptz NOT NULL,

    CONSTRAINT streak_events_event_check CHECK (event IN ('EXTENDED', 'FREEZE_CONSUMED', 'FREEZE_BANKED', 'BROKEN')),
    -- One day changes a run once. A freeze banked on the same day is its own fact.
    CONSTRAINT streak_events_once UNIQUE (bond_id, date, event)
);

-- What was decided for a day when it was evaluated (spec §6.5: "persist the
-- applied strict-mode and freeze events alongside day outcomes so
-- recalculation never substitutes today's setting for past decisions").
--   evaluated_at     when; null until then. A day is evaluated once.
--   evaluated_strict whether the bond was in Strict mode at that moment.
--   freeze_applied   whether this day spent a banked freeze — which is also
--                    what tells a FROZEN day that was a missed one from a
--                    FROZEN date a zone change stepped over.
ALTER TABLE bond_days
    ADD COLUMN evaluated_at     timestamptz,
    ADD COLUMN evaluated_strict boolean,
    ADD COLUMN freeze_applied   boolean,
    ADD CONSTRAINT bond_days_evaluation_check CHECK (
        (evaluated_at IS NULL AND evaluated_strict IS NULL AND freeze_applied IS NULL)
        OR (evaluated_at IS NOT NULL AND closed_at IS NOT NULL AND evaluated_strict IS NOT NULL AND freeze_applied IS NOT NULL)
    );

-- The evaluation's scan: a bond's days that are settled and not yet
-- evaluated, in date order. Partial, so it holds only what is still to do.
CREATE INDEX bond_days_unevaluated_idx ON bond_days (bond_id, date) WHERE closed_at IS NOT NULL AND evaluated_at IS NULL;
