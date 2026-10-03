package com.moyi.gratitude.service

import com.moyi.bond.api.BondAnchorTimeline
import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.BondCalendar
import com.moyi.gratitude.domain.DayWindow
import com.moyi.gratitude.domain.Reader

/**
 * The bond's anchor timeline, as the [BondCalendar] `gratitude.domain` asks
 * questions of (ruling P7). One line of translation and no arithmetic: every
 * date and span still comes from `bond.domain.AnchorTimeline` through
 * [BondAnchorTimeline.dayBoundsAt], so there is one implementation of "which
 * day contains this instant" in the codebase, not two. An instant before
 * [BondAnchorTimeline.beginsAt] is on no day — the bond did not exist — and
 * is answered `null` rather than asked of a timeline that cannot place it.
 *
 * Lives in the service layer because that is where both types may be seen
 * at once — `gratitude.domain` imports nothing from `bond`, and `bond.api`
 * cannot know about `gratitude`.
 */
internal fun BondAnchorTimeline.asCalendar(): BondCalendar =
    BondCalendar { at ->
        if (at.isBefore(beginsAt)) null else dayBoundsAt(at).let { DayWindow(it.date, it.startsAt, it.endsAt) }
    }

/**
 * The caller, as BR-1's gate wants them: their member id together with the
 * bond that membership is of. Both come from the one [BondMembership]
 * `bond.api.BondAccess` resolved — which is what makes the gate's
 * "membership first" a check against a membership somebody actually verified,
 * not against a bond id a request named.
 */
internal fun BondMembership.asReader(): Reader = Reader(memberId = memberId, bondId = bondId)
