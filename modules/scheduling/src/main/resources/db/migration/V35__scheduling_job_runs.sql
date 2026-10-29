-- When each job last FINISHED (Phase 4 D5, ADR-0050; review of #110).
--
-- ShedLock's `locked_at` says a run began, not that it ended: a job that throws
-- on every run takes its lock every hour and looks alive to anything that reads
-- the lock. A job writes its row here only once a run has finished without
-- throwing, and the media alarm (ops/monitoring/media-check.sh) reads this.
--
-- `scheduling`'s own table, beside V16's `shedlock`. Written with the
-- application's clock, which in production is the wall clock.
CREATE TABLE job_runs (
    name            text        PRIMARY KEY,
    last_success_at timestamptz NOT NULL
);
