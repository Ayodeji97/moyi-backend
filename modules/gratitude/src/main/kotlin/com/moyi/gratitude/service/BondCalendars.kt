package com.moyi.gratitude.service

import com.moyi.bond.api.BondAnchorTimeline
import com.moyi.gratitude.domain.BondCalendar
import com.moyi.gratitude.domain.DayWindow

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
