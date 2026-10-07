package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
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
 * that bond stood **when that membership was resolved**. `bond` records a
 * withdrawal in the transaction that ends the bond; the rows are erased
 * later, by the outbox's consumer. Carried here, the fact reaches the gate
 * with every reader there is (spec §6.7). No caller passes the set or decides
 * anything from it: this is the only place it is read, and
 * [com.moyi.gratitude.domain.Entry.canBeReadBy] the only place it is asked.
 *
 * **So the membership this is called on must be one resolved after the
 * entries were loaded, or one read under the bond's lock.** A write path has
 * the second (`lockMembershipOf`: the ending needs that lock, so it has
 * either committed and is in the set, or cannot commit until the writer
 * does). A read path has neither by default, and takes [readerNow].
 */
internal fun BondMembership.asReader(): Reader = Reader(memberId = memberId, bondId = bondId, withdrawnAuthors = withdrawnMemberIds)

/**
 * The reader for a path that holds no lock on the bond: the caller's
 * membership **resolved again, now**, and the [Reader] that one makes.
 *
 * **The marker is read last.** Call this after the entries to be rendered
 * have been loaded, never before. The reason is an ordering of two reads,
 * the entries at T1 and the withdrawals at T2, with T1 before T2:
 *
 * - If the withdrawal is absent at T2, the ending had not committed at T2,
 *   and so not at T1 either. The response is one that could have been given
 *   entirely before the ending.
 * - If it is present at T2, the words are hidden, whatever the rows held.
 *
 * Asked first, the same two reads prove nothing: the set can be stale by as
 * long as anything waits between them, and `ReconcileJoiningDay.beforeRead`
 * waits on the bond's row lock, which the ending's own transaction holds.
 * The read then goes on, after that commit, with a set from before it. (The
 * consumer erasing rows between T1 and T2 changes nothing: a row only ever
 * gets emptier.)
 *
 * It works inside a caller's transaction as well as outside one: the
 * transactions here are `READ COMMITTED`, where each statement sees what has
 * committed by then, and the withdrawals are read by plain JDBC, which no
 * persistence context answers for.
 *
 * It costs a second membership resolution on a read. The port has no
 * narrower question to ask.
 *
 * @throws com.moyi.common.web.NotFoundException the caller is no longer a
 * member. Member rows are never deleted, so this cannot happen today; if it
 * ever can, the answer is the `404` the first resolution would have given.
 */
internal fun BondAccess.readerNow(membership: BondMembership): Reader = membershipOf(membership.userId, membership.bondId).asReader()
