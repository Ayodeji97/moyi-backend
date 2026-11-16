package com.moyi.gratitude.service

import com.moyi.bond.api.DueNudge
import com.moyi.bond.api.ReminderRoster
import com.moyi.gratitude.api.DailyReminders
import com.moyi.gratitude.api.NudgeCounts
import com.moyi.gratitude.api.ReminderRun
import com.moyi.gratitude.domain.ReminderKey
import com.moyi.gratitude.infra.database.WrittenToday
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.Nudge
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * [DailyReminders] (spec §4 N2, ADR-0045, and #200's nudges, ADR-0068):
 * `bond` says whose reminder or nudge fell when, this module says who has
 * written, `notification` carries the push.
 *
 * **Only the member's own writing is asked.** A member who has written that
 * bond-day is not nudged; one who has not is, whether or not the other has.
 * The push says nothing either way: its words are a localisation key the
 * apps hold (`push_reminder_*`), and the server sends no name, no count and
 * nothing about the other member (FR-076, T-09, Q6).
 *
 * **Each nudge asks with its own key** ([ReminderKey]), its index, the
 * earlier keys of its day it supersedes if still unsent (C16), and, for
 * nudges 2 and 3, how many cross-Bond slots it must leave free for reveals
 * still owed (C17): the Bond-days `bond` named, less those `notification`
 * says are settled. Nudge 1 leaves none: no cap may drop the day's reminder.
 *
 * **One transaction per nudge**, so that one member's failure (a database
 * hiccup on their row) costs that nudge and not everyone's after it. A
 * failure is counted and logged by its class name only; the next sweep covers
 * a different window, so it is not retried (a reminder is a hint, doc 25:69).
 *
 * **Stale at the end of the bond-day.** A nudge held by quiet hours past the
 * end of the day it was for is about a day that can no longer be written to,
 * and is `EXPIRED` rather than sent the next morning.
 */
@Service
internal class SendDailyReminders(
    private val roster: ReminderRoster,
    private val written: WrittenToday,
    private val notifications: Notifications,
    transactionManager: PlatformTransactionManager,
) : DailyReminders {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)

    @Suppress("TooGenericExceptionCaught") // One member's failure must not stop the rest; see the class's note.
    override fun remindDue(
        after: Instant,
        upTo: Instant,
    ): ReminderRun {
        val due = roster.remindersDueBetween(after, upTo)
        val outcomes =
            due.map { nudge ->
                val outcome =
                    try {
                        checkNotNull(transactions.execute { remind(nudge) })
                    } catch (thrown: Exception) {
                        log.warn("reminders: one nudge could not be asked for: index={} error={}", nudge.index, thrown.javaClass.name)
                        log.debug("reminders: the nudge that could not be asked for: {}", nudge)
                        Outcome.FAILED
                    }
                nudge.index to outcome
            }
        val byNudge =
            outcomes.groupBy({ it.first }, { it.second }).mapValues { (_, of) ->
                NudgeCounts(
                    requested = of.count { it == Outcome.REQUESTED },
                    written = of.count { it == Outcome.WRITTEN },
                    alreadyAsked = of.count { it == Outcome.ALREADY_ASKED },
                    failed = of.count { it == Outcome.FAILED },
                )
            }
        return ReminderRun(
            due = due.size,
            requested = byNudge.values.sumOf { it.requested },
            alreadyWritten = byNudge.values.sumOf { it.written },
            failed = byNudge.values.sumOf { it.failed },
            byNudge = byNudge,
        )
    }

    private fun remind(due: DueNudge): Outcome {
        val reminder = due.reminder
        if (written.hasWritten(reminder.bondId, reminder.bondDate, reminder.memberId)) return Outcome.WRITTEN
        val asked =
            notifications.request(
                PushRequest(
                    userId = reminder.userId,
                    bondId = reminder.bondId,
                    category = PushCategory.REMINDER,
                    dedupeKey = ReminderKey.of(reminder.memberId, reminder.bondDate, due.index),
                    staleAfter = reminder.bondDayEndsAt,
                    recipient = reminder.recipient,
                    // The Bond-day it reminds of (#146): the app opens Today when it is the current one.
                    bondDate = reminder.bondDate,
                    nudge =
                        Nudge(
                            index = due.index,
                            leaveFreeAcrossBonds = leaveFree(due),
                            supersedes = ReminderKey.before(reminder.memberId, reminder.bondDate, due.index),
                        ),
                    sharedChange = null,
                ),
            )
        return if (asked) Outcome.REQUESTED else Outcome.ALREADY_ASKED
    }

    /**
     * The reveals still owed across the member's Bonds, the nudged one included (C17): those
     * `bond` named, less those already settled. Zero for nudge 1: no cap may drop the reminder.
     */
    private fun leaveFree(due: DueNudge): Int {
        if (due.index == 1 || due.revealDays.isEmpty()) return 0
        val settled =
            notifications.revealClassSettled(
                due.reminder.userId,
                due.revealDays.associate { it.bondId to it.bondDate },
            )
        return due.revealDays.count { it.bondId !in settled }
    }

    private enum class Outcome { REQUESTED, WRITTEN, ALREADY_ASKED, FAILED }
}
