package com.moyi.bond.service

import com.moyi.bond.api.PushAudienceMember
import com.moyi.bond.api.PushAudiences
import com.moyi.bond.domain.BondId
import com.moyi.bond.infra.database.ProposalStore
import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `bond.notify` (spec §4 N3, ADR-0046): the pushes made of what happens to a
 * Bond itself. Two, and each to **the other member only**:
 *
 * - [MemberJoined] → `PARTNER_JOINED` to the member who was already there
 *   (FR-080, D-13). Never to the joiner, who knows.
 * - [ProposalOpened] → `PROPOSAL_OPENED` to the member who did not propose
 *   (the owner's Q13). **Once per proposal, never a reminder**: the dedupe
 *   key is the proposal's, and nothing here or anywhere asks again. A
 *   proposal answered, cancelled or lapsed before this runs is told to
 *   nobody: it no longer waits on anyone.
 *
 * Each asks first whether the Bond takes writes and stops if not (ADR-0041
 * Owed, ADR-0040:141): an ended Bond is told nothing. Each member's own
 * toggle (`partnerJoined`, `proposal`), quiet hours and budget travel with
 * their [com.moyi.notification.api.PushRecipient] and hold back only their
 * copy (Q9).
 *
 * **[StartFrom.NOW]**: a join or a proposal published before this consumer
 * first ran is days old, and the bond has already been opened since.
 *
 * Runs in the delivery's transaction (`MANDATORY`), which is what
 * `Notifications.request` joins: the push is asked for exactly when the
 * delivery is acknowledged. Delivered twice, the dedupe keys ask for nothing
 * the second time.
 */
@Component
internal class NotifyBondMembers(
    private val audiences: PushAudiences,
    private val proposals: ProposalStore,
    private val notifications: Notifications,
    private val clock: Clock,
) : EventConsumer {
    /** The key the delivery state is kept under: never to be changed. */
    override val id = "bond.notify"
    override val eventTypes = setOf(MemberJoined.TYPE, ProposalOpened.TYPE)
    override val startFrom = StartFrom.NOW

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        when (event.eventType) {
            MemberJoined.TYPE -> joined(event)
            ProposalOpened.TYPE -> proposed(event)
        }
    }

    private fun joined(event: ReceivedEvent) {
        val bondId = event.reference(MemberJoined.BOND_ID)
        val joiner = event.reference(MemberJoined.MEMBER_ID)
        tellOthers(bondId, event, PushCategory.PARTNER_JOINED, except = joiner) { userId -> "joined:$bondId:$userId" }
    }

    private fun proposed(event: ReceivedEvent) {
        val bondId = event.reference(ProposalOpened.BOND_ID)
        val proposalId = event.reference(ProposalOpened.PROPOSAL_ID)
        val proposer = event.reference(ProposalOpened.PROPOSER_ID)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        // Still waiting on somebody? Asked by the one predicate that says what "live" is.
        val live = proposals.findAllLiveOf(listOf(BondId(bondId)), now)[BondId(bondId)].orEmpty().any { it.id.value == proposalId }
        if (!live) return
        tellOthers(bondId, event, PushCategory.PROPOSAL_OPENED, except = proposer) { userId -> "proposal:$proposalId:$userId" }
    }

    /**
     * One push to every member of the Bond but [except], if it takes writes. A day after the
     * event the news is stale: the apps show the bond's state when opened (doc 25:69).
     */
    private fun tellOthers(
        bondId: UUID,
        event: ReceivedEvent,
        category: PushCategory,
        except: UUID,
        dedupeKey: (UUID) -> String,
    ) {
        val audience = audiences.audienceOf(bondId) ?: return
        if (!audience.takesWrites) return
        audience.members.filterNot { it.memberId == except }.forEach { member -> request(member, bondId, category, dedupeKey, event) }
    }

    private fun request(
        member: PushAudienceMember,
        bondId: UUID,
        category: PushCategory,
        dedupeKey: (UUID) -> String,
        event: ReceivedEvent,
    ) {
        notifications.request(
            PushRequest(
                userId = member.userId,
                bondId = bondId,
                category = category,
                dedupeKey = dedupeKey(member.userId),
                staleAfter = event.occurredAt.plus(STALE_AFTER),
                recipient = member.recipient,
            ),
        )
    }

    private fun ReceivedEvent.reference(name: String): UUID = checkNotNull(references[name]) { "A $eventType event carries no $name." }

    internal companion object {
        /** Spec §4 N3: a partner joining and a proposal opening are news for a day. */
        val STALE_AFTER: Duration = Duration.ofHours(24)
    }
}
