-- A stretch of time in which a bond refused every write and then took them
-- again: a deletion that counted down and was called off (FR-028, ADR-0030).
--
-- While the countdown runs the bond carries `deletion_requested_at`, and the
-- close job writes no day for it. When the deletion is cancelled that
-- timestamp is cleared, and until this table nothing recorded that the
-- countdown had happened: the job then saw a live bond with a month of days
-- missing, wrote them as EMPTY, and the streak judged them missed. A couple
-- on a thirty-day streak who changed their minds woke to a streak of zero,
-- for a month in which the app would not let them write.
--
-- The owner's ruling, 2026-10-06 (ADR-0034, question 1): those days are
-- SUSPENDED. This is what lets the close job know which days they are.
CREATE TABLE bond_write_pauses (
    bond_id     uuid        NOT NULL REFERENCES bonds (id) ON DELETE CASCADE,
    paused_from timestamptz NOT NULL,
    paused_to   timestamptz NOT NULL,

    PRIMARY KEY (bond_id, paused_from),
    CONSTRAINT bond_write_pauses_span_check CHECK (paused_to >= paused_from)
);
