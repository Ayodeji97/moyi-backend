-- The admin console, slice A2 (spec 2026-11-11-admin-console-a2-design.md §9.1).
--
-- Widens audit_log's action CHECK with the moderation console's actions and the
-- step-up, seventeen in all, and does nothing else. No column is added: what a
-- member's export discloses is derived from the row when the export is built
-- (§9.3), so nothing on this append-only table ever needs to change later.
--
-- The list mirrors the Kotlin enum AuditAction; AuditActionCheckTest holds the
-- two to each other. Rows already written are untouched: every A1 action is
-- still in the list, and a CHECK added to a populated table is validated
-- against those rows.

ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;

ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'ADMIN_INVITED', 'ADMIN_DISABLED', 'ADMIN_ENROLLED', 'ADMIN_SIGN_IN', 'ADMIN_SIGN_OUT', 'USER_LOOKED_UP',
    'REPORT_QUEUE_VIEWED', 'REPORT_READ', 'REPORT_DISMISSED', 'REPORT_RECLASSIFIED', 'REPORT_UPHELD',
    'QUARANTINE_QUEUE_VIEWED', 'MEDIA_VIEWED', 'MEDIA_RELEASED', 'MEDIA_BLOCKED', 'MEDIA_DELETED',
    'ADMIN_STEP_UP'
));
