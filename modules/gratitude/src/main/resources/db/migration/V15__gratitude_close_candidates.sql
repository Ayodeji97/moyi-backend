-- The close job's scan (spec §6.4 step 2): unclosed days that may have ended.
-- V12's bond_days_open_idx is on (status, date) for three statuses; the closer
-- asks by ends_at and must also find SUSPENDED and REVEALED days that carry no
-- closed_at yet. Partial, so it holds only the days still to be settled.
CREATE INDEX bond_days_unclosed_idx ON bond_days (ends_at) WHERE closed_at IS NULL;
