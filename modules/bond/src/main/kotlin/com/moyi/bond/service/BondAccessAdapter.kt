package com.moyi.bond.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondAnchorTimeline
import com.moyi.bond.api.BondDayBounds
import com.moyi.bond.api.BondMembership
import com.moyi.bond.domain.AnchorTimeline
import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondStatus
import com.moyi.bond.domain.DayBounds
import com.moyi.bond.domain.Membership
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.database.AnchorIntervalStore
import com.moyi.bond.infra.database.BondStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The port's only implementation: the guard, plus the bond facts, in one read
 * (F7, whole-branch review — this KDoc previously said four, then five;
 * [BondAccess]'s own KDoc has the current count). `internal`, so the boundary
 * is the interface and not this class.
 *
 * It calls [BondAccessGuard] rather than reimplementing the check, so there is
 * one definition of "is this caller a member" and one 404. The guard's own
 * store read is scoped to the caller, so this is two reads of the same row and
 * not a fetch-then-check anybody could reorder.
 *
 * Both port methods read the guard's [Membership] and the [Bond] themselves
 * and hand both to [assemble] — rather than sharing a private helper keyed on
 * a bare `BondId` — because `ArchitectureTest`'s "a bond service function that
 * names a bond also takes the Membership the guard minted" rule means exactly
 * that: a `com.moyi.bond.service` function may not take a `BondId` without
 * also taking the `Membership` that proves it was checked.
 */
@Service
internal class BondAccessAdapter(
    private val guard: BondAccessGuard,
    private val bonds: BondStore,
    private val anchorIntervals: AnchorIntervalStore,
) : BondAccess {
    @Transactional(readOnly = true)
    override fun membershipOf(
        userId: UUID,
        bondId: UUID,
    ): BondMembership {
        val caller = UserId(userId)
        val id = BondId(bondId)
        val membership = guard.membershipOf(caller, id)
        val bond = bonds.findByMember(id, caller) ?: throw BondNotFoundException()
        return assemble(membership, bond)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockMembershipOf(
        userId: UUID,
        bondId: UUID,
    ): BondMembership {
        val caller = UserId(userId)
        val id = BondId(bondId)
        // Before any read (spec §2.1): everything below sees state no other
        // writer can change out from under it until this transaction commits.
        bonds.lockBond(id)
        val membership = guard.membershipOf(caller, id)
        val bond = bonds.findByMember(id, caller) ?: throw BondNotFoundException()
        return assemble(membership, bond)
    }

    private fun assemble(
        membership: Membership,
        bond: Bond,
    ): BondMembership =
        BondMembership(
            bondId = membership.bondId.value,
            memberId = membership.memberId.value,
            userId = membership.userId.value,
            anchorTimezone = bond.anchorTimezone.id,
            revealTimeLocal = bond.revealTimeLocal,
            strictMode = bond.strictMode,
            isOpen = bond.isOpen,
            hasLeft = membership.left,
            awaitingSecondMember = bond.status == BondStatus.PENDING_MEMBER,
            activeSince = activeSinceOf(bond),
            endedAt = bond.archivedAt,
            anchorTimeline = anchorTimelineOf(membership),
        )

    /**
     * The second member's `joined_at` — the maximum across every member row,
     * current or left — or `null` while the bond has only its creator.
     * `bonds.created_at` would manufacture `EMPTY` days across the whole
     * waiting window (see [BondMembership.activeSince]'s own KDoc).
     */
    private fun activeSinceOf(bond: Bond): Instant? =
        if (bond.status == BondStatus.PENDING_MEMBER) null else bond.members.maxOf { it.joinedAt }

    /**
     * Closes over the real [AnchorTimeline] so [BondAnchorTimeline] can answer
     * every question without ever importing `bond.domain` itself — that type
     * lives in `bond.api`, which (like `domain`) depends on nothing else in
     * this module, so a reference to the domain type cannot appear there. One
     * implementation of the date arithmetic stays in the domain; this closes
     * over it rather than reimplementing any of it.
     */
    private fun anchorTimelineOf(membership: Membership): BondAnchorTimeline {
        val timeline = anchorIntervals.timelineOf(membership.bondId)
        return BondAnchorTimeline(
            zoneIdAtFn = { at -> timeline.zoneAt(at).id },
            dateAtFn = timeline::dateAt,
            dayBoundsAtFn = { at -> timeline.dayBoundsAt(at).toApi() },
            usedLabelsUpToFn = timeline::usedLabelsUpTo,
        )
    }

    private fun DayBounds.toApi(): BondDayBounds = BondDayBounds(date, startsAt, endsAt, isDegenerate)
}
