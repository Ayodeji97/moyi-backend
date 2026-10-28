package com.moyi.gratitude.service

import com.moyi.bond.api.DueReminder
import com.moyi.bond.api.ReminderRoster
import com.moyi.gratitude.api.DailyReminders
import com.moyi.gratitude.api.ReminderRun
import com.moyi.gratitude.infra.database.WrittenToday
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * [DailyReminders] (spec §4 N2, ADR-0045): `bond` says whose reminder fell
 * when, this module says who has written, `notification` carries the push.
 *
 * **Only the member's own writing is asked.** A member who has written that
 * bond-day is not reminded; one who has not is, whether or not the other has.
 * The push says nothing either way: its words are a localisation key the
 * apps hold (`push_reminder_*`), and the server sends no name, no count and
 * nothing about the other member (FR-076, T-09, Q6).
 *
 * **One transaction per member**, so that one member's failure (a database
 * hiccup on their row) costs that member's reminder and not everyone's after
 * it. A failure is counted and logged by its class name only; the next sweep
 * covers a different window, so it is not retried (a reminder is a hint, doc
 * 25:69).
 *
 * **Stale at the end of the bond-day.** A reminder held by quiet hours past
 * the end of the day it was for is about a day that can no longer be written
 * to, and is `EXPIRED` rather than sent the next morning.
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
        var requested = 0
        var alreadyWritten = 0
        var failed = 0
        due.forEach { reminder ->
            try {
                when (transactions.execute { remind(reminder) }) {
                    Outcome.REQUESTED -> requested++
                    Outcome.WRITTEN -> alreadyWritten++
                    else -> Unit // asked before, in an earlier sweep of the same window
                }
            } catch (thrown: Exception) {
                failed++
                log.warn(
                    "reminders: one member's reminder could not be asked for: bond={} error={}",
                    reminder.bondId,
                    thrown.javaClass.name,
                )
            }
        }
        return ReminderRun(due = due.size, requested = requested, alreadyWritten = alreadyWritten, failed = failed)
    }

    private fun remind(reminder: DueReminder): Outcome {
        if (written.hasWritten(reminder.bondId, reminder.bondDate, reminder.memberId)) return Outcome.WRITTEN
        val asked =
            notifications.request(
                PushRequest(
                    userId = reminder.userId,
                    bondId = reminder.bondId,
                    category = PushCategory.REMINDER,
                    dedupeKey = dedupeKey(reminder),
                    staleAfter = reminder.bondDayEndsAt,
                    recipient = reminder.recipient,
                ),
            )
        return if (asked) Outcome.REQUESTED else Outcome.ALREADY_ASKED
    }

    private enum class Outcome { REQUESTED, WRITTEN, ALREADY_ASKED }

    internal companion object {
        /**
         * `reminder:{memberId}:{bond-day label}`. The spec writes `{bondDayId}`;
         * the label stands for it because a bond-day's row is made by the first
         * entry or by the close job, and the day a member is reminded is
         * usually one with no row yet. A bond's labels are unique to it
         * (`bond_days_bond_date_key`), and a member id is unique to one bond, so
         * the key names the same day the id would (ADR-0045).
         */
        fun dedupeKey(reminder: DueReminder): String = "reminder:${reminder.memberId}:${reminder.bondDate}"
    }
}
