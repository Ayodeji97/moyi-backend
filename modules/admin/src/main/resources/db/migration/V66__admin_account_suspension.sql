-- The admin console, slice A3 (spec 2026-11-13-admin-console-a3-design.md §7).
--
-- Widens audit_log's action CHECK by ACCOUNT_SUSPENDED and ACCOUNT_UNSUSPENDED, an administrator's
-- suspension of a member's account and the lifting of it, and does nothing else. As V64 did: drop
-- and re-add the one constraint; rows already written are untouched and every earlier action is
-- still in the list.
--
-- The list mirrors the Kotlin enum AuditAction; AuditActionCheckTest holds the two to each other.

ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;

ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'ADMIN_INVITED', 'ADMIN_DISABLED', 'ADMIN_ENROLLED', 'ADMIN_SIGN_IN', 'ADMIN_SIGN_OUT', 'USER_LOOKED_UP',
    'REPORT_QUEUE_VIEWED', 'REPORT_READ', 'REPORT_DISMISSED', 'REPORT_RECLASSIFIED', 'REPORT_UPHELD',
    'QUARANTINE_QUEUE_VIEWED', 'MEDIA_VIEWED', 'MEDIA_RELEASED', 'MEDIA_BLOCKED', 'MEDIA_DELETED',
    'ADMIN_STEP_UP', 'ENTRY_TEXT_READ', 'ACCOUNT_SUSPENDED', 'ACCOUNT_UNSUSPENDED'
));
