package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.common.security.CurrentUser
import com.moyi.common.web.NotFoundException
import java.util.UUID

/**
 * The guard of a bond-scoped route (T-02): the caller's membership of the bond [bondId] names,
 * or the one `404` for a bond that does not exist, one the caller is not in, and a value that
 * is not a UUID alike. [bondId] is taken as text so that the last of those is this `404` and
 * not Spring's `400`, decided before anything else in the request is read.
 *
 * The routes of slice C6b use it. `DaysController`, `EntriesController` and
 * `StreakController` keep their own copies, which state-map `codeRef`s quote; moving them here
 * is owed (ADR-0039 decision 21).
 */
internal fun BondAccess.guard(
    caller: CurrentUser,
    bondId: String,
): BondMembership {
    val id = runCatching { UUID.fromString(bondId) }.getOrElse { throw NotFoundException("That bond was not found.") }
    return membershipOf(caller.id, id)
}
