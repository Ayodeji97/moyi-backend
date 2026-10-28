-- Push notifications, the member's half (Phase 4 slice N2, ADR-0045):
-- category toggles, quiet hours and FR-082's budget, decided by the
-- `notification.push` consumer when it comes to send.
--
-- `notification` is a leaf (spec §2.1): it cannot read a member's settings,
-- which live on `bond_members`. So the caller resolves them and hands them
-- over with the request, and they are kept here with the push, as they stood
-- when it was asked for. Nothing here is personal beyond what the push
-- already was: a zone, two times of day, and a yes or no.

-- The member's reminder zone in this Bond: the zone their quiet hours are
-- read in, and the zone whose day the per-Bond budget counts (spec D-16).
-- Added with a default so that any row N1 wrote is valid, then the default
-- is dropped: every request names its zone from here on.
ALTER TABLE notifications ADD COLUMN zone text NOT NULL DEFAULT 'UTC';
ALTER TABLE notifications ALTER COLUMN zone DROP DEFAULT;
ALTER TABLE notifications
    ADD CONSTRAINT notifications_zone_length_check CHECK (char_length(zone) BETWEEN 1 AND 64);

-- Quiet hours, both or neither; neither means none (off by default, Q7).
ALTER TABLE notifications ADD COLUMN quiet_hours_start time;
ALTER TABLE notifications ADD COLUMN quiet_hours_end time;
ALTER TABLE notifications
    ADD CONSTRAINT notifications_quiet_hours_paired_check CHECK ((quiet_hours_start IS NULL) = (quiet_hours_end IS NULL));

-- Whether the member wanted this category when it was asked for. False is
-- recorded SUPPRESSED by the consumer, never sent.
ALTER TABLE notifications ADD COLUMN wanted boolean NOT NULL DEFAULT true;

-- HELD: inside quiet hours, waiting for `not_before`, when the release puts
-- it back to REQUESTED. Not final, like REQUESTED.
-- CANCELLED: called off before it went, because its reason has gone: the
-- daily reminder of a member who has written since it was asked for.
ALTER TABLE notifications DROP CONSTRAINT notifications_status_check;
ALTER TABLE notifications
    ADD CONSTRAINT notifications_status_check CHECK (
        status IN ('REQUESTED', 'HELD', 'SENT', 'SUPPRESSED', 'DROPPED_BUDGET', 'EXPIRED', 'CANCELLED', 'FAILED_PERMANENT')
    );

-- Only a sent push took a slot, and only a sent push can have taken the
-- reserved one.
ALTER TABLE notifications
    ADD CONSTRAINT notifications_reserved_sent_check CHECK (NOT reserved OR status = 'SENT');

-- FR-082's two counts, both asked under the member's budget lock: "sent to
-- this member in this Bond since the start of their day", and "sent to this
-- member in the last 24 hours".
CREATE INDEX notifications_budget_idx ON notifications (user_id, sent_at) WHERE status = 'SENT';

-- The release's walk: held pushes in the order their hours end.
CREATE INDEX notifications_held_idx ON notifications (not_before) WHERE status = 'HELD';
