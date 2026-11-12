package com.moyi.export.api

import java.time.Instant
import java.util.UUID

/**
 * **The seam for T-10 and FR-112**: "admin access is disclosed to the affected user in their data
 * export". The admin module implements it (`AdminAccessDisclosure`, A2 spec §9.3), unconditionally:
 * the audit log is kept whether or not the console is switched on. The export asks for one bean of
 * this type; finding none, it says in the archive that no administrator-access log is kept
 * (ADR-0059 decision 8).
 *
 * **Some access is withheld, by rule.** What is listed is decided when the export is built, from
 * each recorded row alone, so that no later event can change what an earlier export said: a view,
 * a release, a block, anything a report drove, and a lookup for a safety case or a law-enforcement
 * request are never listed (A2 spec §9.3, §14 Q1). The archive's statement says so in one sentence
 * that is the same for everyone, so its presence tips no one off.
 */
interface AdminAccessLog {
    /**
     * The recorded administrator access to [userId]'s account or what they wrote that may be
     * disclosed to them, oldest first. Read each time an export is built; writes nothing. Called
     * with no transaction open: the export build holds none.
     */
    fun accessesTo(userId: UUID): List<AdminAccess>
}

/**
 * One recorded access: when, what was done (`ACCOUNT_LOOKED_UP`, `MEDIA_REMOVED`, …), and the
 * justification typed for it to be shown to the person (FR-112, A5's), or null. A moderator's
 * note is never one: it is written for the operator's record.
 */
class AdminAccess(
    val at: Instant,
    val action: String,
    val justification: String?,
) {
    override fun toString(): String = "AdminAccess(at=$at, action=$action)"
}
