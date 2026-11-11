package com.moyi.admin.domain

/**
 * What an audit row says was done. A closed set, and this is the one place it
 * is written: V60's `CHECK` on `audit_log.action` repeats it for the database,
 * and `AuditActionCheckTest` holds the SQL to this list, so adding a value here
 * without a migration fails a test and not a production insert.
 *
 * `ADMIN_INVITED` and `ADMIN_DISABLED` are written by the operator's SQL
 * functions, not by Kotlin; they are listed so the check admits them.
 */
internal enum class AuditAction {
    ADMIN_INVITED,
    ADMIN_DISABLED,
    ADMIN_ENROLLED,
    ADMIN_SIGN_IN,
    ADMIN_SIGN_OUT,
    USER_LOOKED_UP,
}
