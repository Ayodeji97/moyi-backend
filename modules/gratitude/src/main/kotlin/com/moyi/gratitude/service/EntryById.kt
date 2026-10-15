package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.common.web.NotFoundException
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.infra.database.EntryStore
import java.util.UUID

/**
 * For a route that names an entry by id and holds no lock (`FavouriteEntry`,
 * `ReactToEntry`): the entry **as it stands now** and the caller's
 * membership of its bond, resolved after it. One copy, because this is the
 * half of those routes that keeps every never-shown entry one `404`, and
 * the order of its reads is the point.
 *
 * [EntryNotFoundException] when there is no such entry or the caller is not
 * in its bond; the guard's own "no such bond" is not passed on, since that
 * would say the entry exists.
 *
 * The first read grants nothing but the entry's bond, which never changes.
 * The joining day is reconciled as for every gratitude request (spec
 * §12.4): on a day C1 left `SUSPENDED` with both entries, that is the
 * reveal, and the difference between the `404` (or "not yet") and a
 * bookmark or a reaction. **The membership after the entry, never before:
 * the marker is read last** ([membershipNow], which has why). Take
 * `.asReader()` of it for the gate; ask it too whether the bond has ended,
 * where the route refuses on that.
 */
internal fun entryAndMembershipNow(
    access: BondAccess,
    entries: EntryStore,
    joining: ReconcileJoiningDay,
    userId: UUID,
    entryId: EntryId,
): Pair<Entry, BondMembership> =
    try {
        entries.find(entryId)?.bondId?.let { bondId ->
            val membership = access.membershipOf(userId, bondId)
            joining.beforeRead(membership)
            entries.find(entryId)?.let { entry -> entry to access.membershipNow(membership) }
        }
    } catch (_: NotFoundException) {
        null
    } ?: throw EntryNotFoundException()
