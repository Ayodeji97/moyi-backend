-- Phase 4 N3 (ADR-0046): the on-this-day sweep asks, for each member due, whether a push of
-- that category was sent or is still owed to them in the last seven days. Without this the
-- question is a scan of the whole ledger, once per opted-in member per day. Every status is
-- indexed, not only the live ones: the statuses asked about are the caller's to choose.
CREATE INDEX notifications_recipient_idx ON notifications (user_id, category, created_at);
