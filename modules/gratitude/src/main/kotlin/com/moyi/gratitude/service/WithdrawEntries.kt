package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.gratitude.infra.database.EntryStore
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Erases every entry of a member who ended a bond and took their words back
 * (FR-029a, spec §6.7): the outbox's first consumer.
 *
 * **Why an event, and not a call.** The ending is `bond`'s and the entries
 * are `gratitude`'s, and `gratitude` already depends on `bond`; a call back
 * would be a cycle. `bond` records the withdrawal and publishes
 * `EntriesWithdrawn` in the ending's own transaction, and this is handed the
 * event afterwards. The words are unreadable from that commit, whatever
 * happens here: the read gate consults the same record
 * ([com.moyi.gratitude.domain.Entry.canBeReadBy]). This is what makes them
 * gone.
 *
 * **Through [EraseEntry], one entry at a time**, because a withdrawal must
 * leave the entry rows a run of single deletes leaves, and the same answers
 * to every reader (ADR-0035 decision 13; ADR-0028 decision 8: nothing may
 * tell that a withdrawal happened). So no event is published, a settled day
 * is not recounted, and nothing is touched that a `DELETE` would not touch.
 *
 * **A `DELETE` touches one thing more, and this deliberately does not.** A
 * request reconciles the couple's joining day before it does anything else;
 * this is not a request. A joining day nobody has met since the pairing is
 * therefore left `SUSPENDED` here, counted again without the entry, where a
 * delete would have resumed it first. It is the day as it would stand with
 * no withdrawal at all, the next read or the close reconciles it, and both
 * members are answered the same in the meantime (`WithdrawEntriesTest`).
 * Reconciling here would be this consumer doing a read's work, and one more
 * thing to keep in step with it.
 *
 * **The lock order is the application's: bond, day, entry.** The bond's row
 * is taken first, which holds off the close job, a read that reconciles the
 * joining day, and the author's own `DELETE` for as long as this runs; then
 * each entry's day and the entry, inside [EraseEntry]. An author's entries
 * are on many days, and two days of one bond are only ever held oldest
 * first, so the entries are erased **oldest day first**. No test can show
 * that order to be needed today: every other holder of two days takes the
 * bond's lock first, and so cannot be running at the same time as this. It
 * is kept because the rule is what makes the next writer safe, not this one.
 *
 * **It acts on the event's member, and checks the bond's record of it.** An
 * event names one member of one bond; only that member's entries go, however
 * many withdrawals the bond has seen. But erasure cannot be undone, so the
 * event alone is not believed: the bond must itself say this member
 * withdrew. `bond` writes the two together, so an event without the record
 * is not something this code can produce. If one appears it fails, is
 * retried and is counted among the failing deliveries, where somebody will
 * look. Erasing on its word would destroy entries nothing else in the system
 * is hiding.
 *
 * **All of it is one transaction, the delivery's**, so the entries are
 * erased and the delivery acknowledged together or not at all. That is every
 * row lock of the member's history held at once, a few thousand for someone
 * who wrote daily for years, and it is accepted: the bond has ended, nothing
 * writes to it but an author's own delete, and those wait. The dispatcher
 * gives a delivery sixty seconds. Two thousand entries, five and a half
 * years of days, take about 5 s on a developer's laptop
 * (`WithdrawEntriesTest`, 2.5 ms an entry), so the limit is reached near
 * twenty-four thousand. A delivery
 * that did reach it would be rolled back and retried whole, and fail the
 * same way each time; it would show as a failing delivery, and the cure
 * would be to erase in batches that each commit, which redelivery already
 * tolerates because an erased entry is passed over.
 *
 * **Delivered twice, it does nothing the second time**: the entries it would
 * erase are no longer live, and [EraseEntry] writes nothing for one that is
 * already erased. Events arrive in no promised order, and none is needed:
 * each names its own member, and an entry is erased once whoever asks.
 */
@Component
internal class WithdrawEntries(
    private val access: BondAccess,
    private val entries: EntryStore,
    private val eraser: EraseEntry,
    private val clock: Clock,
) : EventConsumer {
    /**
     * The key the delivery state is kept under: never to be changed. It says
     * "withdrawal" and not how the bond ended (ADR-0028 decision 8).
     */
    override val id = "gratitude.withdrawal"
    override val eventTypes = setOf(EVENT)

    /** A withdrawal published before this consumer first ran is owed its erasure all the same. */
    override val startFrom = StartFrom.BEGINNING

    /**
     * `MANDATORY`: this must run in the dispatcher's transaction, the one
     * that acknowledges the delivery. Given a transaction of its own it would
     * commit an erasure the delivery did not record, or the reverse.
     *
     * **A bond that is gone is nothing to do, and returns normally** so that
     * the delivery is acknowledged. Its entries went with it or belong to
     * whatever removed it, and no later attempt could find more: thrown, this
     * would be retried every fifteen minutes for ever and counted as failing.
     *
     * What this throws is never logged with its message: the dispatcher
     * records the class of a handler's exception and nothing else, which is
     * why a database error is not stripped of its row here as it is on the
     * request paths. The two messages written below hold a key's name and no
     * value.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        val bondId = event.reference(BOND_ID)
        val memberId = event.reference(MEMBER_ID)
        val view = access.lockClosingViewOf(bondId) ?: return
        check(memberId in view.withdrawnMemberIds) { "An $EVENT event names a member whose bond records no withdrawal." }
        val now = clock.instant()
        entries.liveOf(bondId, memberId).forEach {
            eraser.erase(it.id, it.bondDayId, now)
            // Or each erasure is slower than the one before it: see the store.
            entries.forgetLoaded()
        }
    }

    /** A missing reference is a fault in what was published: retried and counted, never passed over as delivered. */
    private fun ReceivedEvent.reference(key: String): UUID = checkNotNull(references[key]) { "An $EVENT event carries no '$key'." }

    private companion object {
        const val EVENT = "EntriesWithdrawn"
        const val BOND_ID = "bondId"
        const val MEMBER_ID = "memberId"
    }
}
