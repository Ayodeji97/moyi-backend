package com.moyi.admin.domain

/**
 * What a member's data export shows of one audit row (spec §9.3): the export's word for it,
 * or `null` when the row is withheld. One pure function of the row alone, so that the owner's
 * ruling on §14 Q1 changes this one place, and so that no later event (a block, a report) can
 * change what an earlier export said: a row shown once is shown in every later export, and a
 * row withheld once is withheld in all of them. That stability is the point. A view listed in
 * one export and missing from the next, after the asset was blocked, would tip the uploader off.
 *
 * Shown:
 * - a successful lookup of the member's account whose purpose is disclosed
 *   ([LookupPurpose.disclosed]), as [ACCOUNT_LOOKED_UP];
 * - the successful removal of the member's own media from a moderation quarantine, with no
 *   report behind it (`reportDriven == false`), as [MEDIA_REMOVED].
 *
 * Each action is shown only on the target it is about: a lookup on a `USER` row, a removal on a
 * `MEDIA` one. The query reads both targets for every action, so it is this rule, and no `WHERE`
 * clause, that withholds a `MEDIA_DELETED` row written against an account, or a `USER_LOOKED_UP`
 * row written against one of the member's media.
 *
 * Withheld: everything else. Views and releases, because either could precede a block; every
 * act a report drove, because in a bond of two it names the reporter; every block; lookups for
 * `SAFETY` or `LEGAL`; a lookup with no purpose (a row written before A2); every refusal; and
 * the rows that are about no one member (queue views, report reads and decisions, step-ups).
 * An `ENTRY_TEXT_READ` row is withheld whatever its outcome, target or metadata (A5 spec §9,
 * ruling 2): every such read is report-driven, and in a bond of two the reporter is the partner.
 * An `ACCOUNT_SUSPENDED` or `ACCOUNT_UNSUSPENDED` row is withheld whatever its outcome, target or
 * metadata (A3 spec §10): a suspension is usually report-driven, and in a bond of two the reporter is
 * the partner; the release review owed for withheld rows decides whether a lifted one may be shown.
 * A `MEDIA_DELETED` row whose `reportDriven` is unknown (`null`) is withheld too: only a row
 * that says no report drove it is shown.
 */
internal object Disclosure {
    const val ACCOUNT_LOOKED_UP = "ACCOUNT_LOOKED_UP"
    const val MEDIA_REMOVED = "MEDIA_REMOVED"

    /** The `audit_log.target_type` of a row about a member's account. */
    const val USER = "USER"

    /** The `audit_log.target_type` of a row about one of a member's media. */
    const val MEDIA = "MEDIA"

    fun shows(
        action: AuditAction,
        outcome: AuditOutcome,
        targetType: String?,
        purpose: LookupPurpose?,
        reportDriven: Boolean?,
    ): String? =
        when {
            action == AuditAction.ACCOUNT_SUSPENDED || action == AuditAction.ACCOUNT_UNSUSPENDED -> null
            action == AuditAction.ENTRY_TEXT_READ -> null
            outcome != AuditOutcome.SUCCEEDED -> null
            action == AuditAction.USER_LOOKED_UP && targetType == USER && purpose?.disclosed == true -> ACCOUNT_LOOKED_UP
            action == AuditAction.MEDIA_DELETED && targetType == MEDIA && reportDriven == false -> MEDIA_REMOVED
            else -> null
        }
}
