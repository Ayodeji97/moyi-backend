package com.moyi.export.api

import java.time.Instant
import java.util.UUID

/**
 * **The seam for T-10 and FR-112**: "admin access is disclosed to the affected user in their data
 * export". The admin console (Phase 5, PR #131) is not on `main`, so nothing records an
 * administrator's access yet and nothing implements this. The export asks for one bean of this
 * type and, finding none, says in the archive that no administrator access is recorded
 * (ADR-0059 decision 8). When the admin module keeps its audit log, it implements this, from its
 * own `api` side of the boundary, and the archive carries what it returns with no change here.
 */
interface AdminAccessLog {
    /** Every recorded administrator access to [userId]'s account or what they wrote, oldest first. */
    fun accessesTo(userId: UUID): List<AdminAccess>
}

/**
 * One recorded access: when, what was done (`VIEWED_ENTRY`, `SUSPENDED_ACCOUNT`, …), and the
 * justification typed for it (FR-112), which is written to be shown to the person.
 */
class AdminAccess(
    val at: Instant,
    val action: String,
    val justification: String?,
) {
    override fun toString(): String = "AdminAccess(at=$at, action=$action)"
}
