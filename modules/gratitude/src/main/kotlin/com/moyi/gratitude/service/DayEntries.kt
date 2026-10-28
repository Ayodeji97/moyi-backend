package com.moyi.gratitude.service

import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryReading
import com.moyi.gratitude.domain.Readability
import com.moyi.gratitude.domain.Reader
import java.time.Instant
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

/** One day as one [Reader] is shown it: the status to show, and each side's entry after the gate, or `null` for nothing. */
internal data class ShownDay(
    val status: BondDayStatus,
    val mine: EntryReading?,
    val partners: EntryReading?,
)

/**
 * Every row filed against one day, **as [reader] is shown that day**: one rule for
 * `GET /today` and every route of the archive (the feed, a day alone, search and on this
 * day), so that none of them can show a day another way.
 *
 * Each side is [onEachSideOf]'s row, read through the gate ([Entry.readBy]). Two things
 * more, both the owner's ruling of 2026-10-21 (ADR-0032 question 2, ADR-0036 question 6):
 *
 * - **An entry erased before it was ever shown to [reader] is not there.** The gate's
 *   answer for it is [Readability.TOMBSTONE_UNSEEN], and a day renders that as absence:
 *   `null`, the very value a day the partner never wrote on carries. That the partner
 *   wrote and thought better of it is not the reader's to know (FR-064), so the two days
 *   must be one answer. The gate's answer is unchanged, and favourites and reactions still
 *   answer it as an entry that does not exist; only a day stops drawing it. A tombstone
 *   the reader could once read ([Readability.TOMBSTONE]) is still shown, and their own
 *   entry can never be unseen to them.
 * - **[status] is the day's as its erasures will leave it.** A deletion by hand steps an
 *   unsettled day back in the transaction that erases ([com.moyi.gratitude.domain.BondDay.withoutEntry]),
 *   and the day the partner never wrote on was never stepped forward. A withdrawal hides
 *   its author's entries from its commit, but the day is stepped back only when the
 *   outbox's consumer erases them ([WithdrawEntries]). In between, the row still says
 *   `PARTIAL` or `PENDING_REVEAL` where a deletion by hand says `OPEN` or `PARTIAL`, and
 *   would tell the two apart, and a withdrawal from no entry at all (ADR-0028 decision 8).
 *   So a live entry whose author is among [Reader.withdrawnAuthors] is counted as the
 *   erasure will count it ([BondDayStatus.afterErasure]), and the response does not move
 *   when the erasure runs. A settled day is never stepped back, by either.
 *
 * [status] and [closedAt] are the day's row; [reader] is built after the rows were loaded
 * (`readerNow`), as for every gate.
 */
internal fun Collection<Entry>.shownTo(
    reader: Reader,
    status: BondDayStatus,
    closedAt: Instant?,
): ShownDay {
    val sides = onEachSideOf(reader.memberId)
    val live = filterNot { it.isErased }
    val awaitingErasure = live.count { it.authorMemberId in reader.withdrawnAuthors }
    return ShownDay(
        status = if (awaitingErasure == 0) status else status.afterErasure(closedAt, remaining = live.size - awaitingErasure),
        mine = sides.mine?.shownOnItsDayTo(reader),
        partners = sides.partners?.shownOnItsDayTo(reader),
    )
}

/** The gate's answer, unless it is that [reader] was never shown this entry and it is gone: then nothing. */
private fun Entry.shownOnItsDayTo(reader: Reader): EntryReading? =
    readBy(reader).takeUnless { it.readability == Readability.TOMBSTONE_UNSEEN }
