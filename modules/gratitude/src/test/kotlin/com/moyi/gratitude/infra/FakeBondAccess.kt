package com.moyi.gratitude.infra

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.common.web.NotFoundException
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The `bond.api.BondAccess` port, in memory — [FakeUserDirectory]'s
 * precedent, second use. [BondAccess] is `bond`'s own contract; this module
 * never scans `com.moyi.bond` (see [GratitudeTestApplication]), so a test
 * that needs a caller's place in a bond registers one here instead of
 * booting `bond`'s real persistence and HTTP stack for it.
 *
 * **Every membership it hands back is a real [BondMembership].** Its
 * constructor is `internal` to `bond` on purpose (see the class's own KDoc:
 * "unforgeable outside bond"), so this class cannot build one by hand — it
 * asks [BondMembership.forTesting], the door `bond` left open for exactly
 * this, rather than reaching for a `data class` copy or a second type that
 * would only resemble the real thing.
 *
 * Deliberately not a mock, for `FakeUserDirectory`'s own reason: this lets a
 * test assert what the code did with a real answer, not that it called the
 * port.
 */
internal class FakeBondAccess : BondAccess {
    private val memberships = ConcurrentHashMap<Pair<UUID, UUID>, BondMembership>()

    /** Registers the membership a later [membershipOf] call for this pair answers with. */
    @Suppress("LongParameterList")
    fun register(
        userId: UUID,
        bondId: UUID,
        memberId: UUID = UUID.randomUUID(),
        anchorTimezone: String = "Africa/Lagos",
        revealTimeLocal: LocalTime? = null,
        strictMode: Boolean = false,
        isOpen: Boolean = true,
        hasLeft: Boolean = false,
    ): BondMembership {
        val membership =
            BondMembership.forTesting(
                bondId = bondId,
                memberId = memberId,
                userId = userId,
                anchorTimezone = anchorTimezone,
                revealTimeLocal = revealTimeLocal,
                strictMode = strictMode,
                isOpen = isOpen,
                hasLeft = hasLeft,
            )
        memberships[userId to bondId] = membership
        return membership
    }

    fun clear() = memberships.clear()

    /** @throws NotFoundException nothing was [register]ed for this pair — the same answer the real adapter gives a stranger. */
    override fun membershipOf(
        userId: UUID,
        bondId: UUID,
    ): BondMembership = memberships[userId to bondId] ?: throw NotFoundException("bond")
}
