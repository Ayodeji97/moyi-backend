package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.gratitude.infra.database.EntryReports
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.gratitude.infra.database.Favourites
import com.moyi.gratitude.infra.database.Reactions
import com.moyi.identity.api.AccountDeletions
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * `gratitude`'s share of an account deletion (FR-008, BR-10, doc 07 §7, ADR-0058 decision 8).
 *
 * In every bond the person was ever a member of, open or ended:
 *
 * - **Every entry they wrote is erased through [EraseEntry]**, the routine a `DELETE` and a
 *   withdrawal use, so its words, search projection, vectors, prompt, bookmarks, reaction and
 *   media go the same way (ADR-0035, ADR-0039, ADR-0050, ADR-0054). The row stays as a
 *   tombstone, and a settled day keeps its status, which is what keeps the partner's streak
 *   and milestones (BR-10, ADR-0041 decision 2). A day still being written steps back.
 * - **Every entry of theirs is flagged `author_deleted_account`**, erased before or now
 *   (BR-10; a withdrawal never sets it, doc 07 §7's amendment).
 * - **Every bookmark they made and every reaction they left are deleted** (ADR-0036 and
 *   ADR-0037's Phase 5 items). Their reports keep their reason and lose their detail
 *   (ADR-0053's Owed item).
 *
 * What the partner wrote is the partner's and is not touched (doc 07 §7).
 *
 * **Locks: bond, day, entry**, the application's order, and bonds in id order. One delivery
 * transaction for all of it, as [WithdrawEntries] is, with the same bound (about twenty
 * thousand entries within the dispatcher's sixty seconds).
 *
 * **Delivered twice, it does nothing the second time**: no entry is live, the flag is set,
 * and the rows to delete are gone. The member rows it reads are never removed (`bond` ends
 * the person's bonds and keeps the rows), so it finds the same memberships whether `bond`'s
 * consumer of the same event has run or not.
 */
@Component
@Suppress("LongParameterList") // What an account's share of gratitude touches, each named.
internal class EraseDeletedAccount(
    private val access: BondAccess,
    private val entries: EntryStore,
    private val eraser: EraseEntry,
    private val favourites: Favourites,
    private val reactions: Reactions,
    private val reports: EntryReports,
    private val clock: Clock,
) : EventConsumer {
    /** Never to be changed: delivery state is kept under it. */
    override val id = "gratitude.account-deletion"
    override val eventTypes = setOf(AccountDeletions.EVENT)
    override val startFrom = StartFrom.BEGINNING

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        val userId = checkNotNull(event.references[AccountDeletions.USER_ID]) { "An ${AccountDeletions.EVENT} event carries no user." }
        val memberships = access.membershipsOfAccount(userId).sortedBy { it.bondId }
        val now = clock.instant()
        for (membership in memberships) {
            // The bond's lock first; a bond that is gone has nothing of this person's left here.
            access.lockClosingViewOf(membership.bondId) ?: continue
            entries.liveOf(membership.bondId, membership.memberId).forEach {
                eraser.erase(it.id, it.bondDayId, now)
                entries.forgetLoaded()
            }
            entries.markAuthorDeletedAccount(membership.bondId, membership.memberId)
        }
        val memberIds = memberships.map { it.memberId }
        favourites.removeAllBy(memberIds)
        reactions.removeAllBy(memberIds)
        reports.forgetDetailOf(memberIds)
    }
}
