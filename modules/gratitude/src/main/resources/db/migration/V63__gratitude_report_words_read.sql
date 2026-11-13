-- The admin console, slice A5 (spec 2026-11-12-admin-console-a5-design.md §7).
--
-- Widens entry_report_log's action CHECK with WORDS_READ, and does nothing else:
-- the report's own history says that an ADMIN read the reported entry's words, and
-- who (actor `admin:<accountId>`, no note: the justification lives only in
-- audit_log, and the entry's words are written nowhere). Rows already written are
-- untouched: every earlier action is still in the list, and a CHECK added to a
-- populated table is validated against those rows.

ALTER TABLE entry_report_log DROP CONSTRAINT entry_report_log_action_check;

ALTER TABLE entry_report_log ADD CONSTRAINT entry_report_log_action_check CHECK (action IN (
    'LISTED', 'READ', 'RECLASSIFIED', 'DISMISSED', 'UPHELD', 'WORDS_READ'
));
