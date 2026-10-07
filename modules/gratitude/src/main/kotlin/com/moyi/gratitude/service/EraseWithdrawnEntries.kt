package com.moyi.gratitude.service

import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.infra.database.EntryStore
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Erases, on one day, what a member who withdrew still has live there,
 * **before anything is decided from that day** (FR-029a, spec §6.7).
 *
 * **Why this exists.** A withdrawal is two things at two times: `bond`
 * records it when the bond ends, and the outbox's consumer
 * ([WithdrawEntries]) erases the entries afterwards, in seconds or, when the
 * poller is stopped or a delivery is backing off, much later. Until then the
 * day still counts the entry and the row still looks live, and the reveal
 * and the close read only the day and the row. A day that reached its reveal
 * time in that interval was revealed: the partner was handed the id and
 * timestamps of an entry she was never shown, the member who took their own
 * words back read hers, and the streak counted the day. At midnight the same
 * thing stamped a withdrawn lone entry revealed and closed its day `SOLO`.
 * The stamp outlives the erasure, so which of the two happened depended on
 * who took the bond's lock first.
 *
 * **So whoever reaches the day first does the consumer's work on it.** Not a
 * rule of its own about what a withdrawn entry means for a reveal — "skip
 * it", "count the day as one short" — which would be a second account of
 * what an erasure does, to be kept in step with the first. It is the erasure
 * itself, by [EraseEntry], the routine the consumer and `DELETE` use, and
 * the caller then decides from the day as that left it. The close job before
 * the consumer, the consumer before the close job, and the author's own
 * delete at that instant therefore leave the same rows
 * (`WithdrawalRaceTest`). The consumer, arriving second, finds the entry
 * erased and passes over it.
 *
 * **Every caller that reveals or closes a day calls this first**, holding
 * the bond's lock and the day's, with the day as it read under that lock:
 * [CloseDay] and [ReconcileJoiningDay]. `SubmitEntry` reveals too and does
 * not call it, because it cannot meet one: a withdrawal is only ever
 * recorded on a bond that has ended, and a bond that has ended is refused
 * before a day is touched. A new path that reveals on an ended bond must
 * call it.
 *
 * **[withdrawn] must have been read under the bond's lock**: from the
 * closing view or the membership that `BondAccess` hands out with the lock
 * taken. The ending writes the record under that lock, so a caller holding
 * it sees either all of an ending or none.
 *
 * It is not the read gate. `Entry.canBeReadBy` hides a withdrawn author's
 * words from every reader from the moment the withdrawal commits, whether or
 * not anything has been erased; this is about what gets **written** in that
 * interval. And `Entry.reveal` does not ask about withdrawals itself: an
 * entry does not know its bond's records, and should not. What keeps a
 * withdrawn entry from being revealed is that it is erased by the time
 * anybody asks, and `Entry.reveal` has always refused an erased entry.
 */
@Component
internal class EraseWithdrawnEntries(
    private val entries: EntryStore,
    private val eraser: EraseEntry,
) {
    /**
     * [day] as it now stands: stepped back once for each entry erased, as a
     * delete steps it, or [day] itself when there was nothing to erase. **The
     * caller goes on from what this returns**, and reads the day's entries
     * again if it needs them: [EraseEntry] has written both since [day] was
     * read.
     *
     * A settled day is not stepped back ([BondDay.withoutEntry]), and its
     * entry keeps its reveal stamp: the partner had read it, and that is
     * history. The entry is erased all the same.
     *
     * Costs one read of the day's entries, and only on a bond somebody has
     * withdrawn from; for every other bond [withdrawn] is empty and this
     * returns at once.
     */
    fun on(
        day: BondDay,
        withdrawn: Set<UUID>,
        now: Instant,
    ): BondDay {
        if (withdrawn.isEmpty()) return day
        // Fresh: one of these may have been loaded before the day's lock was taken.
        return entries
            .findForDayFresh(day.id)
            .filter { !it.isErased && it.authorMemberId in withdrawn }
            .fold(day) { _, entry -> eraser.erase(entry.id, day.id, now).second }
    }
}
