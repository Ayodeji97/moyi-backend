-- Account deletion (FR-008, issue #125, ADR-0058): a person asks for their
-- account to be deleted, may call it off for 14 days, and it is carried out
-- after that, well inside the 30 days FR-008 allows.
--
-- Doc 07 §2 sketches this table as
-- `deletion_requests(id, user_id, requested_at, execute_after, cancelled_at, executed_at)`,
-- and this is that table. `execute_after` is the end of the cancellation
-- window (the API calls it `cancellableUntil`); FR-008's 30-day bound is
-- `requested_at + 30 days` and is not stored, because nothing can move it.
--
-- `users.status` does NOT become `PENDING_DELETION` while a request waits
-- (ADR-0058 decision 2): the person must still be able to sign in to cancel,
-- and the status they had (verified or not) must survive a cancellation. The
-- request is this row; the status becomes `DELETED` when it is carried out.
--
-- A row is kept after it is cancelled or carried out: it holds ids and
-- instants and nothing anyone wrote, and it is the record that the account
-- was erased on request, and when.
CREATE TABLE deletion_requests (
    id            uuid        PRIMARY KEY,
    -- No ON DELETE CASCADE: the user row is never removed, only anonymised
    -- (doc 07 §7), and a request must not vanish with anything.
    user_id       uuid        NOT NULL REFERENCES users (id),
    requested_at  timestamptz NOT NULL,
    execute_after timestamptz NOT NULL,
    cancelled_at  timestamptz,
    executed_at   timestamptz,

    CONSTRAINT deletion_requests_window_check CHECK (execute_after > requested_at),
    -- A request ends one way or the other, never both.
    CONSTRAINT deletion_requests_one_ending_check CHECK (cancelled_at IS NULL OR executed_at IS NULL)
);

-- One pending request per person: a second POST while one waits is the same
-- request (the idempotent 202), and this is what makes two at once one row.
CREATE UNIQUE INDEX deletion_requests_one_pending_key
    ON deletion_requests (user_id)
    WHERE cancelled_at IS NULL AND executed_at IS NULL;

-- The job's walk: pending requests, by when they fall due.
CREATE INDEX deletion_requests_due_idx
    ON deletion_requests (execute_after)
    WHERE cancelled_at IS NULL AND executed_at IS NULL;
