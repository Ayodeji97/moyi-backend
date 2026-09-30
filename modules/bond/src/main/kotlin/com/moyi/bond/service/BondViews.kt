package com.moyi.bond.service

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.Invite
import com.moyi.bond.domain.Member
import com.moyi.bond.domain.Proposal
import com.moyi.bond.domain.ProposalKind
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.database.InviteStore
import com.moyi.bond.infra.database.ProposalStore
import com.moyi.identity.api.UserDirectory
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock

/**
 * What a member sees of a bond: the aggregate, the display names identity
 * holds, their own membership, and the live invite with its link.
 *
 * **One assembler, used by create, get and list**, so the three cannot drift
 * into three slightly different shapes of the same response — the thing that
 * makes a generated client's life miserable. It batches deliberately: one
 * directory call and one invite query for however many bonds, rather than a
 * pair per bond, because `GET /bonds` returns up to three and the N+1 would
 * be invisible until it was not.
 *
 * Display names are read at request time rather than denormalised onto
 * `bond_members`. A name that a person changes should change everywhere at
 * once, and a copy in this module would be a second place for identity's data
 * to live and go stale.
 */
@Component
internal class BondViews(
    private val users: UserDirectory,
    private val invites: InviteStore,
    private val proposals: ProposalStore,
    private val links: InviteLinks,
    private val clock: Clock,
) {
    fun of(
        bond: Bond,
        viewer: UserId,
    ): BondView = ofAll(listOf(bond), viewer).single()

    fun ofAll(
        bonds: List<Bond>,
        viewer: UserId,
    ): List<BondView> {
        if (bonds.isEmpty()) return emptyList()
        val names = users.findAll(bonds.flatMap { bond -> bond.members.map { it.userId.value } }.distinct())
        val now = clock.instant()
        val live = this.invites.findLiveOf(bonds.map { it.id }, now)
        // One query for however many bonds, like the invite one above and for
        // the same reason: `GET /bonds` returns up to three, and a query per
        // bond would be an N+1 nobody would notice until there were more.
        val pending = this.proposals.findAllLiveOf(bonds.map { it.id }, now)
        return bonds.map { bond ->
            BondView(
                bond = bond,
                members = bond.members.map { MemberView(it, names[it.userId.value]?.displayName ?: FORMER_MEMBER) },
                // The guard is what decides whether the caller is a member, and
                // it runs before any of this. Reaching here without a membership
                // is a wiring mistake, and it should say so rather than produce
                // a response with a hole in it.
                me = bond.memberOf(viewer) ?: error("the viewer is not a member of this bond; the guard should have refused the request"),
                invite = live[bond.id]?.let { InviteView(it, links.linkFor(it.code)) },
                proposals = pending[bond.id].orEmpty(),
            )
        }
    }

    private companion object {
        /**
         * Someone identity no longer knows: an account erased in Phase 5,
         * whose entries survive as tombstones (BR-10). A placeholder rather
         * than a blank, because the archive still shows what they wrote and a
         * nameless author reads as a bug.
         */
        const val FORMER_MEMBER = "Former member"
    }
}

/** A bond as one member sees it, with everything the HTTP layer needs and nothing it has to look up. */
internal data class BondView(
    val bond: Bond,
    val members: List<MemberView>,
    val me: Member,
    val invite: InviteView?,
    /**
     * Whatever is waiting for the other member to agree to (FR-027, FR-028).
     *
     * A list rather than two fields, because that is what the store returns and
     * the two kinds are one mechanism; [timezoneChange] and [deletion] are the
     * two questions the HTTP layer asks of it.
     */
    val proposals: List<Proposal> = emptyList(),
) {
    val timezoneChange: Proposal? get() = proposals.firstOrNull { it.kind == ProposalKind.TIMEZONE_CHANGE }

    val deletion: Proposal? get() = proposals.firstOrNull { it.kind == ProposalKind.DELETION }
}

internal data class MemberView(
    val member: Member,
    val displayName: String,
)

internal data class InviteView(
    val invite: Invite,
    val link: URI,
)
