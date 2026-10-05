package com.moyi.bond.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondAnchorTimeline
import com.moyi.bond.api.BondClosingView
import com.moyi.bond.api.BondDayBounds
import com.moyi.bond.api.BondMembership
import com.moyi.bond.domain.AnchorTimeline
import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondStatus
import com.moyi.bond.domain.DayBounds
import com.moyi.bond.domain.Membership
import com.moyi.bond.domain.StrictModeHistory
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.database.AnchorIntervalStore
import com.moyi.bond.infra.database.BondClosingStore
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.StrictModeChanges
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
    private val closing: BondClosingStore,
    private val strictModeChanges: StrictModeChanges,
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
        // Guard first, unlocked — the same order `ChangeTimezone`, `EndBond`,
        // `RequestDeletion` and `UpdateBond` all use (e.g. `EndBond.lockAndLoad`):
        // guard, then the bond's row lock, then a re-read under it. A non-member
        // must never take `FOR UPDATE` on a bond that is not theirs — locking
        // first would do exactly that, for any syntactically valid id.
        guard.membershipOf(caller, id)
        // `refreshReads`: the guard above has already loaded the bond and its
        // members into this transaction, and without the refresh the "re-read"
        // below would be answered from that pre-lock copy (see lockBond's KDoc).
        bonds.lockBond(id, refreshReads = true)
        // Re-read under the lock: between the check above and the lock, a
        // concurrent write could have changed what either read would answer.
        val membership = guard.membershipOf(caller, id)
        val bond = bonds.findByMember(id, caller) ?: throw BondNotFoundException()
        return assemble(membership, bond)
    }

    @Transactional(readOnly = true)
    override fun closingViewOf(bondId: UUID): BondClosingView? {
        val id = BondId(bondId)
        return closing.find(id)?.let { bond ->
            BondClosingView(
                bondId = bondId,
                activeSince = activeSinceOf(bond),
                // Archived, or counting down to deletion: either way it takes no
                // more writes from here, which is what the closer means by ended.
                endedAt = bond.archivedAt ?: bond.deletionRequestedAt,
                revealTimeLocal = bond.revealTimeLocal,
                strictModeBeforeFn = StrictModeHistory(bond.strictMode, strictModeChanges.of(id))::before,
                anchorTimeline = apiTimelineOf(anchorIntervals.timelineOf(id)),
            )
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockClosingViewOf(bondId: UUID): BondClosingView? {
        val id = BondId(bondId)
        bonds.lockBond(id)
        return closing.find(id)?.let { bond ->
            BondClosingView(
                bondId = bondId,
                activeSince = activeSinceOf(bond),
                endedAt = bond.archivedAt ?: bond.deletionRequestedAt,
                revealTimeLocal = bond.revealTimeLocal,
                anchorTimeline = apiTimelineOf(anchorIntervals.timelineOf(id)),
            )
        }
    }

    @Transactional(readOnly = true)
    override fun bondsToSweep(
        after: UUID?,
        limit: Int,
    ): List<UUID> = closing.everPairedAfter(after, limit)

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
            anchorTimeline = apiTimelineOf(anchorIntervals.timelineOf(membership.bondId)),
        )

    /**
     * The second member's `joined_at` — the second-earliest across every
     * member row, current or left — or `null` while the bond has only ever
     * had one member row.
     *
     * **Derived from the member rows, not from `bond.status`.** `status ==
     * PENDING_MEMBER` is *not* the same fact: `Bond.end` archives a bond with
     * only one member exactly as readily as a two-member one (a creator who
     * leaves, or requests deletion, before anyone accepts), so a status check
     * alone would report the creator's own `joinedAt` as `activeSince` on a
     * one-member `ARCHIVED` or `PENDING_DELETION` bond — the exact harm this
     * field exists to prevent (fix round 1, Important #1). Left members keep
     * their row (`states.md` §9), so this still answers correctly once a
     * second member has joined and later left.
     */
    private fun activeSinceOf(bond: Bond): Instant? =
        bond.members
            .map { it.joinedAt }
            .sorted()
            .getOrNull(1)

    /**
     * Closes over the real [AnchorTimeline] so [BondAnchorTimeline] can answer
     * every question without ever importing `bond.domain` itself — that type
     * lives in `bond.api`, which (like `domain`) depends on nothing else in
     * this module, so a reference to the domain type cannot appear there. One
     * implementation of the date arithmetic stays in the domain; this closes
     * over it rather than reimplementing any of it.
     */
    private fun apiTimelineOf(timeline: AnchorTimeline): BondAnchorTimeline =
        BondAnchorTimeline(
            beginsAt = timeline.intervals.first().effectiveFrom,
            zoneIdAtFn = { at -> timeline.zoneAt(at).id },
            dateAtFn = timeline::dateAt,
            dayBoundsAtFn = { at -> timeline.dayBoundsAt(at).toApi() },
            usedLabelsUpToFn = timeline::usedLabelsUpTo,
        )

    private fun DayBounds.toApi(): BondDayBounds =
        BondDayBounds(
            date = date,
            startsAt = startsAt,
            endsAt = endsAt,
            isDegenerate = isDegenerate,
        )
}
