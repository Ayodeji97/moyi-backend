package com.moyi.admin.domain

/**
 * What an audit row says was done. A closed set, and this is the one place it
 * is written: the `CHECK` on `audit_log.action` (V60, widened by V61, V64, V66, V67 and V69) repeats it
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
 *
 * A3 (V66): `ACCOUNT_SUSPENDED` and `ACCOUNT_UNSUSPENDED`, an administrator's suspension of a member's
 * account and the lifting of it.
 *
 * A4 (V67, ADR-0067): `JOB_RUN` (the close job run by hand, written before the run), `STREAK_RECALCULATED`
 * (a bond's streak rebuilt from its days, FR-074), `FEATURE_FLAG_CHANGED` (FR-115), and the support inbox's
 * three: `SUPPORT_INBOX_VIEWED`, `SUPPORT_TICKET_READ`, `SUPPORT_TICKET_CLOSED`.
 *
 * V69 (ADR-0058, amended 2026-11-16): `ACCOUNT_DELETION_REQUESTED`, an account deletion opened by the
 * operator for a person who asked by email. Written by the operator's SQL function
 * `account_deletion_request`, never by Kotlin, as `ADMIN_INVITED` is; [Disclosure] withholds it by its
 * default, and an export of an account it names cannot follow the deletion anyway.
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
    ACCOUNT_SUSPENDED,
    ACCOUNT_UNSUSPENDED,
    JOB_RUN,
    STREAK_RECALCULATED,
    FEATURE_FLAG_CHANGED,
    SUPPORT_INBOX_VIEWED,
    SUPPORT_TICKET_READ,
    SUPPORT_TICKET_CLOSED,
    ACCOUNT_DELETION_REQUESTED,
}
