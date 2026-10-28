package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.DueReminder
import com.moyi.bond.api.PushAudiences
import com.moyi.gratitude.api.OnThisDayPushes
import com.moyi.gratitude.api.OnThisDayRun
import com.moyi.gratitude.domain.PushKeys
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/**
 * [OnThisDayPushes] (FR-092, ADR-0046): `bond` says who opted in and whose
 * push time fell, this module says whether they have a memory today, and
 * `notification` carries the push.
 *
 * **"Would `GET /on-this-day` answer them non-empty" is asked of the very
 * service that answers it** ([GetDays.onThisDay]), with the member's own
 * membership, at the moment of asking. Not a second query that could drift
 * from it: it already holds every rule of that answer (the bond's today, the
 * days the member can read, nothing on a bond that takes no writes from
 * them, tombstones that are not memories), and a push promising a memory the
 * screen then does not show would be a lie told on a lock screen. Not the
 * controller nor its response type either: the answer is read, and only its
 * emptiness is used.
 *
 * **Private** (FR-092): the member's own answer only, which holds nothing
 * they cannot already read. The other member's opt-in is never read, and the
 * push carries the localisation keys `push_on_this_day_*` and the Bond's id,
 * nothing else (Q6).
 *
 * **One transaction per member**, as [SendDailyReminders], for its reason: one
 * member's failure costs that member's push and not everyone's after it.
 *
 * **Stale at the end of the bond-day**: a memory is "on this day", and the
 * next day it is not.
 */
@Service
internal class SendOnThisDayPushes(
    private val audiences: PushAudiences,
    private val access: BondAccess,
    private val getDays: GetDays,
    private val notifications: Notifications,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) : OnThisDayPushes {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)

    @Suppress("TooGenericExceptionCaught") // One member's failure must not stop the rest; see the class's note.
    override fun pushDue(
        after: Instant,
        upTo: Instant,
    ): OnThisDayRun {
        val due = audiences.onThisDayDueBetween(OnThisDayPushes.PUSH_AT, after, upTo)
        var requested = 0
        var nothing = 0
        var failed = 0
        due.forEach { member ->
            try {
                when (transactions.execute { push(member) }) {
                    Outcome.REQUESTED -> requested++
                    Outcome.NOTHING_TO_REMEMBER -> nothing++
                    else -> Unit // asked already this week, or its day is over
                }
            } catch (thrown: Exception) {
                failed++
                log.warn("on this day: one member's push could not be asked for: bond={} error={}", member.bondId, thrown.javaClass.name)
            }
        }
        return OnThisDayRun(due = due.size, requested = requested, nothingToRemember = nothing, failed = failed)
    }

    @Suppress("ReturnCount") // One return per reason nothing is asked, in the order they are asked.
    private fun push(member: DueReminder): Outcome {
        val membership = access.membershipOf(member.userId, member.bondId)
        val memories = getDays.onThisDay(membership)
        // The sweep looks back an hour, so the member's midday can belong to a bond-day that has
        // since ended (review of #100). The answer above is then the next day's, and a push asked
        // for under this day's key and deadline would be expired at once and spend the week's key.
        // Read after the answer: if the day has not ended now, it had not when the answer was read.
        if (!clock.instant().isBefore(member.bondDayEndsAt)) return Outcome.DAY_OVER
        if (memories.isEmpty()) return Outcome.NOTHING_TO_REMEMBER
        val asked =
            notifications.request(
                PushRequest(
                    userId = member.userId,
                    bondId = member.bondId,
                    category = PushCategory.ON_THIS_DAY,
                    dedupeKey = PushKeys.onThisDay(member.memberId, member.bondDate),
                    staleAfter = member.bondDayEndsAt,
                    recipient = member.recipient,
                ),
            )
        return if (asked) Outcome.REQUESTED else Outcome.ALREADY_ASKED
    }

    private enum class Outcome { REQUESTED, NOTHING_TO_REMEMBER, ALREADY_ASKED, DAY_OVER }
}
