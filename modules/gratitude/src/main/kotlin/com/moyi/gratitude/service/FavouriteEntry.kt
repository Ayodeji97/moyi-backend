package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.common.web.NotFoundException
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryReading
import com.moyi.gratitude.domain.Favouriting
import com.moyi.gratitude.domain.Readability
import com.moyi.gratitude.domain.Reader
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.gratitude.infra.database.Favourites
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * `PUT` and `DELETE /entries/{entryId}/favourite` (FR-093, spec §6.6): a
 * member's private bookmark on an entry they can read.
 *
 * **Who may mark what is the read gate's answer for the caller**
 * ([Entry.canBeFavouritedBy], which asks [Entry.canBeReadBy] and has the
 * table). Nothing here looks at a day's status, at who the author is, or at
 * the row's own columns to decide it.
 *
 * **This is not `ChangeEntry.authorOf`'s rule, on purpose** (ADR-0032 asked
 * each new route by entry id to use that rule or say why not). `authorOf`
 * answers "may this caller change the entry", and only its author may. A
 * favourite changes nothing of the entry: doc 04 makes it a row of the
 * member's own, keyed by member, so either member may mark either entry
 * they can read, their partner's above all. What the two rules share is the
 * refusal: everybody the entry was never shown to gets the one
 * [EntryNotFoundException], for every cause, so that a `409` can never
 * confirm an id the caller was not given. That is why "not yet revealed" is
 * told only to the entry's own author, and a partner asking about the same
 * entry is told it does not exist.
 *
 * **Allowed on a bond that has ended, and to a member who has left.** No
 * `hasLeft` or `isOpen` check, where every other write has one. BR-9 closes
 * an ended bond to writes of what the two people share; a bookmark is the
 * member's own, the other person cannot see it in any response, and the
 * archive is what an ended bond is for. (The plan's decision 14, which is
 * the owner's to turn.)
 *
 * **The marker is read last** ([readerNow]): the entry is loaded, and only
 * then is the membership resolved again for the reader the gate is asked
 * with. The first resolution says whether the caller is in the entry's bond
 * and which joining day to reconcile, and can be older than an ending that
 * committed while that reconcile waited on the bond's lock.
 *
 * **No transaction and no lock of its own.** Each step commits by itself,
 * and the order of the last two is what matters:
 *
 * 1. The gate, which is the only thing that knows about a withdrawal whose
 *    entries are still whole.
 * 2. The statement ([Favourites.mark]), which takes the mark only if the
 *    row is still revealed and unerased at that moment. It waits for an
 *    erasure in flight, two seconds at most. An erasure that commits after
 *    the gate answered and before the statement ran leaves nothing to
 *    insert, and is answered as the tombstone it now is; so is one that
 *    holds the row past the wait, because a row held that long is being
 *    erased by a withdrawal.
 *
 * **Neither verb waits out a withdrawal.** The unmark deletes only a row it
 * can lock at once and is `204` either way ([Favourites.unmark]). Before
 * that, each request stuck behind a long withdrawal held a pooled
 * connection, and a few of them stalled every other request there was.
 *
 * One interleaving is let through: a **withdrawal** that commits between
 * the two. The row is whole, so the mark is made, by a request whose answer
 * was decided before the ending. Nothing shows it (every later read is a
 * tombstone, and a tombstone says `false`), and `EraseEntry` removes it with
 * the rest when the consumer or the close job reaches the entry.
 */
@Service
internal class FavouriteEntry(
    private val access: BondAccess,
    private val entries: EntryStore,
    private val favourites: Favourites,
    private val joining: ReconcileJoiningDay,
    private val clock: Clock,
) {
    /**
     * Marks the entry for the caller. Repeatable: a second mark is a success
     * and changes nothing.
     *
     * @throws EntryNotFoundException the entry was never shown to the caller, for any reason.
     * @throws EntryNotRevealedException the caller's own entry, which the partner cannot read yet.
     * @throws EntryImmutableException a tombstone, or an entry erased, or still being erased, while this request was deciding.
     */
    fun mark(
        userId: UUID,
        entryId: EntryId,
    ) {
        val (entry, reader) = entryAndReader(userId, entryId)
        // For an entry the gate allows, the statement is the second line: nothing inserted means it was erased since.
        val refusal =
            when (entry.canBeFavouritedBy(reader)) {
                Favouriting.ALLOWED -> if (favourites.mark(entryId, reader.memberId, clock.instant())) null else EntryImmutableException()
                Favouriting.NOT_YET_REVEALED -> EntryNotRevealedException()
                Favouriting.ERASED -> EntryImmutableException()
                Favouriting.NEVER_SHOWN -> EntryNotFoundException()
            }
        if (refusal != null) throw refusal
    }

    /**
     * Removes the caller's mark. **Absent is success**: whether there was a
     * mark, whether the entry is revealed yet, and whether it has since been
     * erased make no difference to a caller the entry was shown to. On a
     * tombstone it still deletes, which costs nothing and takes away a mark
     * left by the one interleaving the class KDoc describes.
     *
     * @throws EntryNotFoundException as [mark]: an unmark must not be a way to ask whether an id exists.
     */
    fun unmark(
        userId: UUID,
        entryId: EntryId,
    ) {
        val (entry, reader) = entryAndReader(userId, entryId)
        if (entry.canBeFavouritedBy(reader) == Favouriting.NEVER_SHOWN) throw EntryNotFoundException()
        favourites.unmark(entryId, reader.memberId)
    }

    /**
     * The entry **as it stands now** and the reader to ask the gate with,
     * built after it. [EntryNotFoundException] when there is no such entry or
     * the caller is not in its bond; the guard's own "no such bond" is not
     * passed on, since that would say the entry exists.
     *
     * The first read grants nothing but the entry's bond, which never
     * changes. The joining day is reconciled as for every gratitude request
     * (spec §12.4): on a day C1 left `SUSPENDED` with both entries, that is
     * the reveal, and the difference between "not yet" and a mark.
     */
    private fun entryAndReader(
        userId: UUID,
        entryId: EntryId,
    ): Pair<Entry, Reader> =
        try {
            entries.find(entryId)?.bondId?.let { bondId ->
                val membership = access.membershipOf(userId, bondId)
                joining.beforeRead(membership)
                // The reader after the entry, never before: the marker is read last.
                entries.find(entryId)?.let { entry -> entry to access.readerNow(membership) }
            }
        } catch (_: NotFoundException) {
            null
        } ?: throw EntryNotFoundException()
}

/**
 * The caller's own mark on an entry they have just been answered about: for
 * the responses that carry one entry (a write and its replay). `false`
 * without a query unless the gate answered in full, since nothing else may
 * say `true`; the rule itself is `EntryResponse.of`'s, which forces it again.
 */
internal fun Favourites.isMarkedBy(
    membership: BondMembership,
    reading: EntryReading,
): Boolean {
    val id = reading.disclosed?.id?.takeIf { reading.readability == Readability.FULL } ?: return false
    return id in markedBy(membership.memberId, listOf(id))
}
