package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.common.web.NotFoundException
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryReading
import com.moyi.gratitude.domain.Reacting
import com.moyi.gratitude.domain.ReactionType
import com.moyi.gratitude.domain.Readability
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.gratitude.infra.database.Reactions
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * `PUT` and `DELETE /entries/{entryId}/reaction` (FR-048): the one small
 * acknowledgement a member leaves on their partner's entry, changes, or
 * takes back.
 *
 * **Who may react to what is the read gate's answer for the caller**
 * ([Entry.canBeReactedToBy], which asks [Entry.canBeReadBy] and has the
 * table). Like `FavouriteEntry` and unlike `ChangeEntry.authorOf`: the
 * author is the one member who may **not**. What all three share is the
 * refusal: everybody the entry was never shown to gets the one
 * [EntryNotFoundException], for every cause, so that no `409` here can
 * confirm an id the caller was not given.
 *
 * **Not on a bond that has ended, nor by a member who has left: `409
 * BOND_ARCHIVED`.** This is where a reaction parts from a bookmark, which is
 * allowed there. A bookmark is the member's own and the other person never
 * sees it; a reaction is made to be seen, and after an ending that is
 * contact. Taking one's own back is always allowed, as deleting one's own
 * entry is. The ending is told last of the refusals, to a caller who could
 * otherwise have reacted, so a withdrawn author's entry answers as any
 * other tombstone does.
 *
 * **The marker is read last, and the bond's state with it**
 * ([membershipNow]): the entry is loaded, and only then is the membership
 * resolved again, for the reader the gate is asked with and for whether the
 * bond has ended. The first resolution says whether the caller is in the
 * entry's bond and which joining day to reconcile, and can be older than an
 * ending that committed while that reconcile waited on the bond's lock.
 *
 * **No transaction and no lock of its own**, and in particular not the
 * bond's. Each step commits by itself:
 *
 * 1. The gate and the membership, which are the only things that know of a
 *    withdrawal whose entries are still whole, or of an ending.
 * 2. The statement ([Reactions.set]), which writes only if the row is still
 *    revealed, unerased and somebody else's at that moment. It waits for an
 *    erasure in flight, two seconds at most; an erasure that commits first,
 *    or holds the row past the wait, is answered as the tombstone it is.
 *
 * **Neither verb waits out a withdrawal** ([Reactions] has why): taking the
 * bond's lock here would put a reaction behind a delivery that can run to
 * sixty seconds, holding a pooled connection. Taking one back from an
 * entry read in full while an erasure holds its row is refused at once as a
 * conflict to retry, never answered as done: that erasure may roll back
 * ([unreact] has where that stops).
 *
 * So one interleaving is let through: an **ending** that commits between
 * the two steps. The reaction is then made, by a request whose answer was
 * decided before the ending, on a bond that ended a moment later. If the
 * ending withdrew the entry, nothing shows it (every later read is a
 * tombstone, which says `null`) and `EraseEntry` removes it. If it did not,
 * the author sees a reaction that was sent before the ending and landed
 * just after it (ADR-0037 decision 5 and question 4: the owner's to turn).
 */
@Service
internal class ReactToEntry(
    private val access: BondAccess,
    private val entries: EntryStore,
    private val reactions: Reactions,
    private val joining: ReconcileJoiningDay,
) {
    /**
     * Leaves [type] on the entry as the caller's reaction, in place of any
     * they had left. Repeatable.
     *
     * @throws EntryNotFoundException the entry was never shown to the caller, for any reason.
     * @throws ReactionOnOwnEntryException the caller wrote it.
     * @throws EntryImmutableException a tombstone, or an entry erased, or still being erased, while this request was deciding.
     * @throws BondArchivedException the bond has ended, or the caller has left it.
     */
    fun react(
        userId: UUID,
        entryId: EntryId,
        type: ReactionType,
    ) {
        val (entry, membership) = entryAndMembership(userId, entryId)
        val refusal =
            when (entry.canBeReactedToBy(membership.asReader())) {
                Reacting.NEVER_SHOWN -> {
                    EntryNotFoundException()
                }

                Reacting.OWN_ENTRY -> {
                    ReactionOnOwnEntryException()
                }

                Reacting.ERASED -> {
                    EntryImmutableException()
                }

                Reacting.ALLOWED -> {
                    when {
                        // Both are checked, as on every write (`BondArchivedException` has why).
                        membership.hasLeft || !membership.isOpen -> BondArchivedException()

                        // The statement is the second line: nothing written means the entry was erased since the gate answered.
                        reactions.set(entryId, membership.memberId, type) -> null

                        else -> EntryImmutableException()
                    }
                }
            }
        if (refusal != null) throw refusal
    }

    /**
     * Takes the caller's reaction back. **Absent is success**: whether there
     * was one, whether the entry is the caller's own (which can hold none of
     * theirs), whether it has since been erased and whether the bond has
     * ended make no difference to a caller the entry was shown to.
     *
     * **One refusal of its own, and it comes last:** a row another
     * transaction holds, on a partner's entry the caller reads **in full**,
     * is `409 REACTION_BUSY`, and the caller tries again. There the holder
     * may be an erasure that rolls back, and the reaction would then still
     * be shown after a removal reported as made.
     *
     * **On a tombstone a held row is `204` all the same.** The entry is a
     * tombstone to the caller by something that has committed, a tombstone
     * shows no reaction whatever rows there are, and what holds the row is
     * the erasure that removes it (a delivery that fails is retried). A
     * `409` there would also be the one answer that differs between a
     * withdrawal whose delivery is still open and its twin deleted by hand,
     * for as long as the delivery runs.
     *
     * @throws EntryNotFoundException as [react]: this must not be a way to ask whether an id exists.
     * @throws ReactionBusyException another transaction holds the reaction on an entry read in full; no removal is promised.
     */
    fun unreact(
        userId: UUID,
        entryId: EntryId,
    ) {
        val (entry, membership) = entryAndMembership(userId, entryId)
        val answer = entry.canBeReactedToBy(membership.asReader())
        if (answer == Reacting.NEVER_SHOWN) throw EntryNotFoundException()
        val removed = reactions.remove(entryId, membership.memberId)
        // Only where a rollback could bring it back into view: the caller's own entry can hold no row of theirs.
        if (!removed && answer == Reacting.ALLOWED) throw ReactionBusyException()
    }

    /**
     * The entry **as it stands now** and the caller's membership of its
     * bond, resolved after it. [EntryNotFoundException] when there is no
     * such entry or the caller is not in its bond; the guard's own "no such
     * bond" is not passed on, since that would say the entry exists.
     *
     * The first read grants nothing but the entry's bond, which never
     * changes. The joining day is reconciled as for every gratitude request
     * (spec §12.4): on a day C1 left `SUSPENDED` with both entries, that is
     * the reveal, and the difference between the `404` and a reaction.
     */
    private fun entryAndMembership(
        userId: UUID,
        entryId: EntryId,
    ): Pair<Entry, BondMembership> =
        try {
            entries.find(entryId)?.bondId?.let { bondId ->
                val membership = access.membershipOf(userId, bondId)
                joining.beforeRead(membership)
                // The membership after the entry, never before: the marker is read last.
                entries.find(entryId)?.let { entry -> entry to access.membershipNow(membership) }
            }
        } catch (_: NotFoundException) {
            null
        } ?: throw EntryNotFoundException()
}

/**
 * The reaction on an entry the caller has just been answered about: for the
 * responses that carry one entry (a write's replay). `null` without a query
 * unless the gate answered in full, since nothing else may show one; the
 * rule itself is `EntryResponse.of`'s, which forces it again.
 */
internal fun Reactions.on(reading: EntryReading): ReactionType? {
    val id = reading.disclosed?.id?.takeIf { reading.readability == Readability.FULL } ?: return null
    return on(listOf(id))[id]
}
