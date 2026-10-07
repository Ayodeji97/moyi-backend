package com.moyi.gratitude.service

import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.infra.database.BondDayStore
import com.moyi.gratitude.infra.database.EntryStore
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Erases one entry: the words and media references go, the row stays as a
 * tombstone, and a day that was still being written is counted again
 * without it (BR-10).
 *
 * **One routine, because two things must leave the same rows.** An author
 * deleting their own entry (`DELETE /entries/{entryId}`, [ChangeEntry]) and
 * a withdrawal erasing every entry of a member who took their words back
 * (FR-029a) are told apart by nothing a reader of the database, or of the
 * API, can see: that is what keeps a withdrawal discreet (ADR-0028 decision
 * 8). Two implementations would agree until one of them was changed. So the
 * withdrawal calls this, once per entry, and a withdrawal is then a run of
 * single deletes by construction.
 *
 * **The caller holds the bond's lock**, taken through `BondAccess`, inside
 * a transaction. This takes the day's lock and
 * then the entry's, which completes the application's one order: bond,
 * bond-day, entry. Called without the bond's lock it would still erase, and
 * could do so across an ending or a close that believed the bond was held.
 * A caller erasing entries on more than one day takes the older day first.
 *
 * It decides nothing about **who** may erase. The caller has.
 */
@Component
internal class EraseEntry(
    private val days: BondDayStore,
    private val entries: EntryStore,
) {
    /**
     * The entry and its day as they now stand.
     *
     * A day that is not settled steps back ([BondDay.withoutEntry]); a
     * settled one is history and does not change, whatever is erased from
     * it afterwards. **Idempotent:** an entry already erased is returned as
     * it is, its day is not stepped back a second time, and nothing is
     * written. That is what lets a delete be repeated, and lets an author's
     * own delete and a withdrawal reach the same entry in either order.
     *
     * The day's lock may already be this transaction's ([ChangeEntry] takes
     * it first, to order it against the joining day). Taking a row lock the
     * transaction holds waits for nobody and adds no edge to the lock
     * order; it is taken here regardless so that this routine is safe for a
     * caller that has not.
     *
     * [now] is cut to microseconds here, so `deleted_at` reads back as it
     * was written whoever the caller is.
     */
    fun erase(
        entryId: EntryId,
        dayId: BondDayId,
        now: Instant,
    ): Pair<Entry, BondDay> {
        val day = days.lockAndFind(dayId)
        val entry = entries.lockAndFind(entryId)
        // A day that is not the entry's would be stepped back for an entry it never counted.
        check(entry.bondDayId == day.id) { "entry ${entry.id.value} is not filed on day ${day.id.value}" }
        val erased = entry.erase(now.truncatedTo(ChronoUnit.MICROS))
        // Only for the erasure that happens now: one already made has had its step back, or its day was settled.
        val nextDay = if (entry.isErased) day else day.withoutEntry()
        if (erased != entry) entries.update(erased)
        if (nextDay != day) days.update(nextDay)
        return erased to nextDay
    }
}
