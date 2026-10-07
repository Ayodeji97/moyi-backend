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
 *
 * **And who in that bond has withdrawn their entries**
 * ([BondMembership.withdrawnMemberIds]), from the same membership and so as
 * that bond stood when this request resolved it. `bond` records a withdrawal
 * in the transaction that ends the bond; the rows are erased later, by the
 * outbox's consumer. Carried here, the fact reaches the gate with every
 * reader there is, and a read that begins after that commit shows none of the
 * words whether or not anything has been erased yet (spec §6.7). No caller
 * passes the set or decides anything from it: this is the only place it is
 * read, and [com.moyi.gratitude.domain.Entry.canBeReadBy] the only place it
 * is asked.
 */
internal fun BondMembership.asReader(): Reader = Reader(memberId = memberId, bondId = bondId, withdrawnAuthors = withdrawnMemberIds)
