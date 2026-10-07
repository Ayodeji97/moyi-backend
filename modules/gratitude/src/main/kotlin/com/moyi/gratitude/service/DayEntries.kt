package com.moyi.gratitude.service

import com.moyi.gratitude.domain.Entry
import java.util.UUID

/** The two entries a day is rendered from, for one member: theirs and the other person's, either of which may not exist. */
internal data class DayEntries(
    val mine: Entry?,
    val partners: Entry?,
)

/**
 * Of every row filed against one day, **which is "mine" and which is the
 * partner's**, for the member [memberId]: one rule for `GET /today` and for
 * every day of the archive, so the two cannot come to render one day
 * differently.
 *
 * A day can hold more than one row by the same author: an erasure keeps the
 * row as a tombstone and frees the slot (ADR-0031), so an entry deleted and
 * written again is two rows. **A live row is chosen before an erased one, and
 * among equals the newest**, with the id as the last tie-break so the choice
 * never depends on the order rows came back in. A replacement is therefore
 * never hidden behind the tombstone it replaced.
 *
 * A routing decision and not a security one: this picks rows and shows
 * nothing. What a member may see of either is `Entry.readBy`'s alone.
 */
internal fun Collection<Entry>.onEachSideOf(memberId: UUID): DayEntries {
    val ordered = sortedWith(compareBy<Entry> { it.isErased }.thenByDescending { it.createdAt }.thenBy { it.id.value })
    return DayEntries(
        mine = ordered.firstOrNull { it.authorMemberId == memberId },
        partners = ordered.firstOrNull { it.authorMemberId != memberId },
    )
}
