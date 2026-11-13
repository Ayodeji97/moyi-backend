-- The admin console, slice A5 (spec 2026-11-12-admin-console-a5-design.md §9).
--
-- Widens audit_log's action CHECK by ENTRY_TEXT_READ, an administrator's read of one reported
-- entry's words, and does nothing else. As V61 did: drop and re-add the one constraint; rows
-- already written are untouched and every earlier action is still in the list.
--
-- The list mirrors the Kotlin enum AuditAction; AuditActionCheckTest holds the two to each other.

ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;

ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'ADMIN_INVITED', 'ADMIN_DISABLED', 'ADMIN_ENROLLED', 'ADMIN_SIGN_IN', 'ADMIN_SIGN_OUT', 'USER_LOOKED_UP',
    'REPORT_QUEUE_VIEWED', 'REPORT_READ', 'REPORT_DISMISSED', 'REPORT_RECLASSIFIED', 'REPORT_UPHELD',
    'QUARANTINE_QUEUE_VIEWED', 'MEDIA_VIEWED', 'MEDIA_RELEASED', 'MEDIA_BLOCKED', 'MEDIA_DELETED',
    'ADMIN_STEP_UP', 'ENTRY_TEXT_READ'
));
