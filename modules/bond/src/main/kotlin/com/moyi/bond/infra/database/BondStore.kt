package com.moyi.bond.infra.database

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondStatus
import com.moyi.bond.domain.Invite
import com.moyi.bond.domain.UserId
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * The Bond aggregate, spoken in domain terms — the `AccountStore` precedent.
 *
 * It exists because "create a bond" is one thing and three tables, and a
 * service holding three repositories to express one operation is holding the
 * wrong collaborators. It also keeps the mappers where they belong: the
 * service deals in domain objects and never imports a `toEntity`.
 *
 * **Every read is scoped to a user** (doc 05 §5.5 layer 3): there is
 * deliberately no `find(bondId)` on this class. A caller who cannot say who
 * is asking cannot ask — which is the repository-level half of T-02's
 * defence, and the half that keeps working when somebody later writes a
 * service and forgets the guard.
 *
 * Not transactional itself: the boundary is the caller's (doc 18 §4), because
 * the interesting operations here have to sit inside one the caller opened —
 * the FR-025 count under its advisory lock, and B2's accept.
 */
@Component
internal class BondStore(
    private val bonds: BondRepository,
    private val members: BondMemberRepository,
    private val invites: BondInviteRepository,
) {
    /** The bond, its member rows and its first invite: one aggregate, three tables, three inserts. */
    fun insert(
        bond: Bond,
        invite: Invite,
    ) {
        bonds.save(bond.toEntity())
        members.saveAll(bond.members.map { it.toEntity() })
        invites.save(invite.toEntity())
    }

    /**
     * The bond if [userId] holds a membership row in it — current **or**
     * left — and `null` otherwise, which the guard turns into the 404 that
     * does not confirm the id exists (doc 06 §2).
     *
     * The member rows are read first because they *are* the authorisation:
     * the bond row is fetched only once the caller is known to be entitled to
     * see it, so a stranger's request never loads the object it is not allowed
     * to know about.
     */
    fun findByMember(
        bondId: BondId,
        userId: UserId,
    ): Bond? {
        val rows = members.findAllByBondId(bondId.value)
        if (rows.none { it.userId == userId.value }) return null
        return bonds.findById(bondId.value)?.toDomain(rows)
    }

    /**
     * Every bond the user holds a membership row in, newest first
     * (`GET /bonds`). Three queries regardless of how many bonds come back,
     * rather than one per bond.
     */
    fun findAllByMember(userId: UserId): List<Bond> {
        val mine = members.findAllByUserId(userId.value).map { it.bondId }.distinct()
        if (mine.isEmpty()) return emptyList()
        val rows = members.findAllByBondIdIn(mine).groupBy { it.bondId }
        return bonds
            .findAllByIdIn(mine)
            .map { it.toDomain(rows[it.getId()].orEmpty()) }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Must be called before [countOpenBondsOf] in the transaction that then
     * creates or joins — see [BondRepository.lockBondsOf] for the race it
     * closes.
     */
    fun lockBondsOf(userId: UserId) {
        bonds.lockBondsOf(userId.value)
    }

    /** FR-025's number: bonds this user is still in and can still be written to. */
    fun countOpenBondsOf(userId: UserId): Int = members.countByUserIdAndBondStatusIn(userId.value, OPEN_STATUSES).toInt()

    /** The live invite of each of [bondIds], keyed by bond; absent where there is none. */
    fun findLiveInvites(
        bondIds: Collection<BondId>,
        now: Instant,
    ): Map<BondId, Invite> {
        if (bondIds.isEmpty()) return emptyMap()
        return invites
            .findAllLiveByBondIdIn(bondIds.map { it.value }, now)
            .map { it.toDomain() }
            .associateBy { it.bondId }
    }

    private companion object {
        /** FR-025 counts a bond waiting for its partner exactly as much as one that has them. */
        val OPEN_STATUSES = listOf(BondStatus.PENDING_MEMBER, BondStatus.ACTIVE)
    }
}
