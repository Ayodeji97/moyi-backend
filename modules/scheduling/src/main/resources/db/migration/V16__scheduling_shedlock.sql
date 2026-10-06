-- ShedLock's table (spec §6.4, §12.3): one row per job name, held by whichever
-- instance is running it. On Postgres, not Redis: Redis fails open in this
-- system (ADR-0023), which is right for a rate limit and wrong for a lock.
--
-- The shape is the library's own, INCLUDING `timestamp` WITHOUT a time zone,
-- which is the opposite of every other table here and is deliberate. The lock
-- is taken with the database's clock (`usingDbTime`), and ShedLock writes and
-- compares `timezone('utc', CURRENT_TIMESTAMP)` — a zoneless UTC value. In a
-- `timestamptz` column that value is read as local time in the session's
-- zone, so on any connection not in UTC a lock that had lapsed looked as far
-- in the future as the zone's offset, and the job did not run until it
-- passed. Found by a test on a machine at UTC+1; `CloseJobTest` holds it.
CREATE TABLE shedlock (
    name       varchar(64)  NOT NULL PRIMARY KEY,
    lock_until timestamp(3) NOT NULL,
    locked_at  timestamp(3) NOT NULL,
    locked_by  varchar(255) NOT NULL
);
