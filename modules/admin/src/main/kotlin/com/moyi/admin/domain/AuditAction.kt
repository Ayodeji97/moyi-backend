package com.moyi.admin.domain

/**
 * What an audit row says was done. A closed set, and this is the one place it
 * is written: the `CHECK` on `audit_log.action` (V60, widened by V61 and V64) repeats it
 * for the database, and `AuditActionCheckTest` holds the SQL to this list, so
 * adding a value here without a migration fails a test and not a production insert.
 *
 * `ADMIN_INVITED` and `ADMIN_DISABLED` are written by the operator's SQL
 * functions, not by Kotlin; they are listed so the check admits them.
 *
 * The A2 actions (spec §9.1): the moderation console's queue views, reads and
 * decisions, the review copy of a held-back asset (`MEDIA_VIEWED`), and
 * `ADMIN_STEP_UP`, the second factor proven again before an irreversible act.
 *
 * A5 (V64): `ENTRY_TEXT_READ`, an administrator's read of one reported entry's words.
 */
internal enum class AuditAction {
    ADMIN_INVITED,
    ADMIN_DISABLED,
    ADMIN_ENROLLED,
    ADMIN_SIGN_IN,
    ADMIN_SIGN_OUT,
    USER_LOOKED_UP,
    REPORT_QUEUE_VIEWED,
    REPORT_READ,
    REPORT_DISMISSED,
    REPORT_RECLASSIFIED,
    REPORT_UPHELD,
    QUARANTINE_QUEUE_VIEWED,
    MEDIA_VIEWED,
    MEDIA_RELEASED,
    MEDIA_BLOCKED,
    MEDIA_DELETED,
    ADMIN_STEP_UP,
    ENTRY_TEXT_READ,
}
