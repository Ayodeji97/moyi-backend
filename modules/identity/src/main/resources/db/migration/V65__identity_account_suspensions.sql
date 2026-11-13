-- Account suspension (admin console A3, FR-113; spec
-- docs/superpowers/specs/2026-11-13-admin-console-a3-design.md §7, rulings 6 and 15).
--
-- One row per stretch an account was suspended: opened by the suspension, closed
-- (`lifted_at`) by the lift. `users.status = 'SUSPENDED'` holds the account's state; this
-- table holds *when*, because `bond` and `gratitude` judge a day by whether a member was
-- suspended during it, long after the lift (spec §4). The invariant "a live account is
-- SUSPENDED exactly when it has an open span" is kept by the one port that writes both
-- (`AccountSuspension`) and asserted by its test; it cannot be a CHECK.
--
-- No actor and no reason are kept here: those are the admin's `audit_log` row, and
-- `identity` does not know admins. The row holds ids and instants only, so it outlives an
-- erasure (ruling 15): the user id it names is the anonymised account's, as `blocks` keeps.
-- Instants are written truncated to microseconds: they are compared with day bounds.
CREATE TABLE account_suspensions (
    id           uuid        PRIMARY KEY,
    -- No ON DELETE CASCADE: the user row is never removed, only anonymised (doc 07 §7).
    user_id      uuid        NOT NULL REFERENCES users (id),
    suspended_at timestamptz NOT NULL,
    lifted_at    timestamptz,

    CONSTRAINT account_suspensions_lifted_after_check CHECK (lifted_at IS NULL OR lifted_at >= suspended_at)
);

-- One open span per account: what makes a second suspension impossible to record twice,
-- and the index `AccountStanding.suspendedAmong` reads.
CREATE UNIQUE INDEX account_suspensions_one_open_key
    ON account_suspensions (user_id)
    WHERE lifted_at IS NULL;

-- Every span of a member, in order: what a bond's closing view reads (`suspensionsOf`).
CREATE INDEX account_suspensions_user_idx
    ON account_suspensions (user_id, suspended_at);
