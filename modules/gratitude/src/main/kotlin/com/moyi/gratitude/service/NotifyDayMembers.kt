package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.PushAudience
import com.moyi.bond.api.PushAudienceMember
import com.moyi.bond.api.PushAudiences
import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.PushKeys
import com.moyi.gratitude.infra.database.BondDayStore
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `gratitude.notify` (spec §4 N3, ADR-0046): the pushes made of what happens
 * to a Bond-day. Three events, and **only these three**:
 *
 * - `DayRevealed` → `REVEAL` to **both** members (FR-061, FR-065: one each,
 *   no content).
 * - `DayClosed` → `SOLO_UNLOCK` to **the partner who did not write**, and
 *   only when the day closed `SOLO` with its lone entry opened to them
 *   (FR-063, the owner's Q8). A `SOLO` day whose bond had separated before
 *   it ended keeps its entry its author's (ADR-0033 decision 9): nothing was
 *   opened, so nobody is told. Every other status a close leaves (`REVEALED`,
 *   `EMPTY`, `FROZEN`, a withheld `PENDING_REVEAL`) opens nothing new: a
 *   `REVEALED` close was told by its `DayRevealed`.
 * - `MilestoneReached` → `MILESTONE` to **both** members (ADR-0041 H-M5).
 *
 * **Never `StreakBroken` or `StreakExtended`** (ADR-0034:240, decision 11: a
 * push that a streak ended is the guilt FR-076 forbids, and one that it went
 * on is the countdown pressure doc 02 rules out), and **nothing for a
 * reaction**, which publishes no event at all (ADR-0037 decision 10).
 * `ArchitectureTest` holds that no consumer names a streak event, and
 * `DayPushesTest` that this one's set is exactly the three.
 *
 * **The decision is the Bond's, made once** (H-M5, Q9): if the Bond takes no
 * writes (ended, left, blocked, counting down), nobody is told anything, which
 * is what ADR-0041 and ADR-0040:141 owe for an ended bond's last milestones;
 * otherwise each member's push is asked for, and their own toggle, quiet hours
 * and budget may hold back only their copy, in `notification`. The solo push
 * goes to one member because there is only one to whom anything opened.
 *
 * **Nothing in a push says who wrote or who did not** (FR-076, T-09): the
 * reveal and the solo unlock are each worded as an opening (localisation keys
 * `push_reveal_*` and `push_solo_unlock_*`, Q6), and the server sends no text,
 * name or count.
 *
 * **[StartFrom.NOW]** (ADR-0041 Owed): `BEGINNING` would push every reveal and
 * milestone ever reached on the first deploy.
 *
 * Runs in the delivery's transaction (`MANDATORY`), which `Notifications.request`
 * joins. Delivered twice, the dedupe keys ask for nothing the second time.
 */
@Component
internal class NotifyDayMembers(
    private val audiences: PushAudiences,
    private val access: BondAccess,
    private val days: BondDayStore,
    private val entries: EntryStore,
    private val notifications: Notifications,
) : EventConsumer {
    /** The key the delivery state is kept under: never to be changed. */
    override val id = "gratitude.notify"
    override val eventTypes = setOf(DAY_REVEALED, DAY_CLOSED, MILESTONE_REACHED)
    override val startFrom = StartFrom.NOW

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        when (event.eventType) {
            DAY_REVEALED -> revealed(event)
            DAY_CLOSED -> closed(event)
            MILESTONE_REACHED -> milestone(event)
        }
    }

    // Each early return is one reason nobody is told, in the order they are asked; nested, they hide that.
    @Suppress("ReturnCount")
    private fun revealed(event: ReceivedEvent) {
        val day = days.find(BondDayId(event.aggregateId)) ?: return
        if (day.revealedAt == null) return
        val audience = audienceTakingWrites(day.bondId) ?: return
        val staleAfter = endOfFollowingDay(day) ?: return
        audience.members.forEach { member ->
            request(member, day.bondId, PushCategory.REVEAL, PushKeys.reveal(day.id, member.userId), staleAfter)
        }
    }

    @Suppress("ReturnCount") // As [revealed]: one return per reason nobody is told.
    private fun closed(event: ReceivedEvent) {
        val day = days.find(BondDayId(event.aggregateId)) ?: return
        if (day.status != BondDayStatus.SOLO) return
        // The lone entry, if it was opened: the close reveals it only when the bond was still two
        // people's at the day's end, and that stamp is the one fact that says so.
        val author =
            entries
                .findForDay(day.id)
                .filter { !it.isErased && it.revealedAt != null }
                .singleOrNull()
                ?.authorMemberId ?: return
        val audience = audienceTakingWrites(day.bondId) ?: return
        val staleAfter = endOfFollowingDay(day) ?: return
        audience.members.filterNot { it.memberId == author }.forEach { partner ->
            request(partner, day.bondId, PushCategory.SOLO_UNLOCK, PushKeys.soloUnlock(day.id, partner.userId), staleAfter)
        }
    }

    private fun milestone(event: ReceivedEvent) {
        val bondId = event.reference(BOND_ID)
        val bondDayId = event.reference(BOND_DAY_ID)
        val audience = audienceTakingWrites(bondId) ?: return
        val staleAfter = event.occurredAt.plus(MILESTONE_STALE_AFTER)
        audience.members.forEach { member ->
            request(member, bondId, PushCategory.MILESTONE, PushKeys.milestone(bondDayId, member.userId), staleAfter)
        }
    }

    /** The Bond-level half of the decision: `null`, and nobody is told. */
    private fun audienceTakingWrites(bondId: UUID): PushAudience? = audiences.audienceOf(bondId)?.takeIf { it.takesWrites }

    /**
     * Spec §4 N3: a reveal or an opening is worth telling until the end of the Bond-day after
     * the one it happened on, so a push held overnight by quiet hours still arrives, and one
     * the outbox could not deliver for a day does not arrive the day after that. The following
     * day is the bond's own (its anchor timeline), whatever zone change lies between.
     */
    private fun endOfFollowingDay(day: BondDay): Instant? =
        access
            .closingViewOf(day.bondId)
            ?.anchorTimeline
            ?.dayBoundsAt(day.endsAt)
            ?.endsAt

    private fun request(
        member: PushAudienceMember,
        bondId: UUID,
        category: PushCategory,
        dedupeKey: String,
        staleAfter: Instant,
    ) {
        notifications.request(
            PushRequest(
                userId = member.userId,
                bondId = bondId,
                category = category,
                dedupeKey = dedupeKey,
                staleAfter = staleAfter,
                recipient = member.recipient,
            ),
        )
    }

    private fun ReceivedEvent.reference(key: String): UUID = checkNotNull(references[key]) { "A $eventType event carries no '$key'." }

    internal companion object {
        const val DAY_REVEALED = "DayRevealed"
        const val DAY_CLOSED = "DayClosed"
        const val MILESTONE_REACHED = "MilestoneReached"
        private const val BOND_ID = "bondId"
        private const val BOND_DAY_ID = "bondDayId"

        /** Spec §4 N3: a milestone is news for a day. */
        val MILESTONE_STALE_AFTER: Duration = Duration.ofHours(24)
    }
}
