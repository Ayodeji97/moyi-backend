package com.moyi.bond.service

import com.moyi.bond.api.PushAudience
import com.moyi.bond.api.PushAudienceMember
import com.moyi.bond.api.PushAudiences
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.ProposalId
import com.moyi.bond.domain.ProposalOutcome
import com.moyi.bond.infra.database.ProposalStore
import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.notification.api.CancelReason
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRequest
import com.moyi.notification.api.SharedChange
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `bond.notify` (spec §4 N3, ADR-0046, ADR-0069): the pushes made of what
 * happens to a Bond itself. Three, and each to **the other member only**:
 *
 * - [MemberJoined] → `PARTNER_JOINED` to the member who was already there
 *   (FR-080, D-13). Never to the joiner, who knows.
 * - [ProposalOpened] → `PROPOSAL_OPENED` to the member who did not propose
 *   (the owner's Q13). **Once per proposal, never a reminder**: the dedupe
 *   key is the proposal's, and nothing here or anywhere asks again. A
 *   proposal answered, cancelled or lapsed before this runs is told to
 *   nobody: it no longer waits on anyone.
 * - [BondSettingsChanged] and [ProposalClosed] → `SHARED_SETTING_CHANGED`
 *   (issue #205) to every member who has not left **except the one who
 *   acted**: `SETTING` for a change, and for a proposal's ending the outcome
 *   its row reads ([com.moyi.bond.domain.Proposal.outcome]): accepted,
 *   declined or withdrawn. A proposal's ending first calls off its opening
 *   push still owed, for every member (ADR-0069 decision 3); a withdrawal that
 *   called off a member's opening push tells that member nothing more, since
 *   they were never told of the proposal. Keyed `shared:{eventId}:{userId}`:
 *   the event's own id names the fact, and a redelivery carries the same id.
 *
 * The first two ask whether the Bond takes writes and stop if not (ADR-0041
 * Owed, ADR-0040:141). The shared-setting push asks only whether it has
 * **ended** (ADR-0069 decision 4): a deletion agreed puts the Bond into a
 * countdown that takes no writes, and the agreement, and calling the
 * countdown off, are exactly what the other member must hear. An ended Bond
 * is told nothing by any of them. Each member's own
 * toggle (`partnerJoined`, `proposal`, `sharedSettings`), quiet hours and budget travel with
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
    private val meters: MeterRegistry,
) : EventConsumer {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The key the delivery state is kept under: never to be changed. */
    override val id = "bond.notify"
    override val eventTypes = setOf(MemberJoined.TYPE, ProposalOpened.TYPE, BondSettingsChanged.TYPE, ProposalClosed.TYPE)
    override val startFrom = StartFrom.NOW

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        when (event.eventType) {
            MemberJoined.TYPE -> joined(event)
            ProposalOpened.TYPE -> proposed(event)
            BondSettingsChanged.TYPE -> settingsChanged(event)
            ProposalClosed.TYPE -> proposalClosed(event)
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
        tellOthers(bondId, event, PushCategory.PROPOSAL_OPENED, except = proposer) { userId -> openingKey(proposalId, userId) }
    }

    private fun settingsChanged(event: ReceivedEvent) {
        val bondId = event.reference(BondSettingsChanged.BOND_ID)
        val actor = event.reference(BondSettingsChanged.MEMBER_ID)
        val audience = audiences.audienceOf(bondId) ?: return
        tellShared(audience, event, SharedChange.SETTING, actor, calledOff = emptySet())
    }

    private fun proposalClosed(event: ReceivedEvent) {
        val bondId = event.reference(ProposalClosed.BOND_ID)
        val proposalId = event.reference(ProposalClosed.PROPOSAL_ID)
        val actor = event.reference(ProposalClosed.MEMBER_ID)
        // The row is the truth: how it ended is read, never carried (ADR-0035).
        val proposal = proposals.findInBond(BondId(bondId), ProposalId(proposalId)) ?: return
        val audience = audiences.audienceOf(bondId) ?: return
        // ADR-0069 decision 3: no longer waiting on anybody, so its opening push still owed goes
        // nowhere, the actor's own included (a member who answers in the night is not told of it
        // in the morning). One key per member, to know whose was called off.
        val calledOff =
            audience.members
                .filter { notifications.cancel(listOf(openingKey(proposalId, it.userId)), CancelReason.PROPOSAL_CLOSED) > 0 }
                .map { it.memberId }
                .toSet()
        // Lapsed, ended by the bond, or unrecorded: no event is published for these (ProposalClosed's KDoc).
        sharedChangeOf(proposal.outcome)?.let { change -> tellShared(audience, event, change, actor, calledOff) }
    }

    /**
     * `SHARED_SETTING_CHANGED` to every member of [audience] but [actor], unless the Bond has
     * ended; a withdrawal skips a member in [calledOff]. Each member considered is counted once.
     */
    private fun tellShared(
        audience: PushAudience,
        event: ReceivedEvent,
        change: SharedChange,
        actor: UUID,
        calledOff: Set<UUID>,
    ) {
        audience.members.filterNot { it.memberId == actor }.forEach { member ->
            val outcome =
                when {
                    audience.hasEnded -> {
                        BOND_ENDED
                    }

                    change == SharedChange.PROPOSAL_WITHDRAWN && member.memberId in calledOff -> {
                        NEVER_TOLD
                    }

                    notifications.request(sharedRequest(member, audience.bondId, change, event)) -> {
                        REQUESTED
                    }

                    else -> {
                        ALREADY_ASKED
                    }
                }
            meters.counter(SHARED_CHANGES, "change", change.name.lowercase(), "outcome", outcome).increment()
            log.debug("bond.notify: {} in bond {} for member {}: {}", change, audience.bondId, member.memberId, outcome)
        }
    }

    private fun sharedRequest(
        member: PushAudienceMember,
        bondId: UUID,
        change: SharedChange,
        event: ReceivedEvent,
    ) = PushRequest(
        userId = member.userId,
        bondId = bondId,
        category = PushCategory.SHARED_SETTING_CHANGED,
        dedupeKey = "shared:${event.id}:${member.userId}",
        staleAfter = event.occurredAt.plus(STALE_AFTER),
        recipient = member.recipient,
        // About the Bond, not one of its days.
        bondDate = null,
        nudge = null,
        sharedChange = change,
    )

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
                // A partner joining and a proposal opening are about the Bond, not one of its days (#146).
                bondDate = null,
                nudge = null,
                sharedChange = null,
            ),
        )
    }

    private fun ReceivedEvent.reference(name: String): UUID = checkNotNull(references[name]) { "A $eventType event carries no $name." }

    internal companion object {
        /** Spec §4 N3: a partner joining, a proposal opening and a shared change are news for a day. */
        val STALE_AFTER: Duration = Duration.ofHours(24)

        /** Spec §9: one per member considered for a shared change; tags `change` and `outcome`, never an id. */
        const val SHARED_CHANGES = "bond.notify.shared_changes"
        const val REQUESTED = "requested"
        const val ALREADY_ASKED = "already_asked"
        const val BOND_ENDED = "bond_ended"
        const val NEVER_TOLD = "never_told"

        /** The opening push's key: asked under it by `proposed`, called off under it by `proposalClosed`. */
        private fun openingKey(
            proposalId: UUID,
            userId: UUID,
        ): String = "proposal:$proposalId:$userId"

        /** What a proposal's ending tells the other member; `null`, nothing (nobody acted). */
        private fun sharedChangeOf(outcome: ProposalOutcome): SharedChange? =
            when (outcome) {
                ProposalOutcome.ACCEPTED -> SharedChange.PROPOSAL_ACCEPTED
                ProposalOutcome.DECLINED -> SharedChange.PROPOSAL_DECLINED
                ProposalOutcome.WITHDRAWN -> SharedChange.PROPOSAL_WITHDRAWN
                ProposalOutcome.LAPSED, ProposalOutcome.BOND_ENDED, ProposalOutcome.UNRECORDED -> null
            }
    }
}
