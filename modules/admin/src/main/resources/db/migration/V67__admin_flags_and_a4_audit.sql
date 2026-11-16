-- The admin console, slice A4 (spec 2026-11-14-admin-console-a4-design.md §6, ADR-0067).
--
-- feature_flags: the switches an ADMIN turns at runtime (FR-115). Doc 07 sketched
-- `(key pk, enabled, rollout_percent, payload jsonb, updated_at, updated_by)`; the percentage and
-- the payload are not built (ADR-0067 decision 9) and adding them later is additive.
--
-- The table starts EMPTY. A flag with no row is its default (`FeatureFlag.defaultOn`, every one of
-- them on), so this migration changes no behaviour until someone switches a flag.
--
-- The key list mirrors the Kotlin enum com.moyi.common.core.FeatureFlag; FeatureFlagCheckTest holds
-- the two to each other. `updated_by` is the admin who last switched it: admins are this module's,
-- so the foreign key stays inside the module.
CREATE TABLE feature_flags (
    key        text        PRIMARY KEY,
    enabled    boolean     NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid        NOT NULL REFERENCES admin_accounts (id),

    CONSTRAINT feature_flags_key_check CHECK (key IN ('DAILY_REMINDERS', 'ON_THIS_DAY_PUSHES', 'AI_EMBEDDINGS'))
);

-- audit_log's action CHECK, widened by A4's six actions and nothing else (V66's pattern: drop and
-- re-add the one constraint; rows already written are untouched and every earlier action is kept).
-- The list mirrors the Kotlin enum AuditAction; AuditActionCheckTest holds the two to each other.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;

ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'ADMIN_INVITED', 'ADMIN_DISABLED', 'ADMIN_ENROLLED', 'ADMIN_SIGN_IN', 'ADMIN_SIGN_OUT', 'USER_LOOKED_UP',
    'REPORT_QUEUE_VIEWED', 'REPORT_READ', 'REPORT_DISMISSED', 'REPORT_RECLASSIFIED', 'REPORT_UPHELD',
    'QUARANTINE_QUEUE_VIEWED', 'MEDIA_VIEWED', 'MEDIA_RELEASED', 'MEDIA_BLOCKED', 'MEDIA_DELETED',
    'ADMIN_STEP_UP', 'ENTRY_TEXT_READ', 'ACCOUNT_SUSPENDED', 'ACCOUNT_UNSUSPENDED',
    'JOB_RUN', 'STREAK_RECALCULATED', 'FEATURE_FLAG_CHANGED',
    'SUPPORT_INBOX_VIEWED', 'SUPPORT_TICKET_READ', 'SUPPORT_TICKET_CLOSED'
));
