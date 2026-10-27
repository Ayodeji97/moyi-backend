package com.moyi.bond.service

import com.moyi.bond.api.DueReminder
import com.moyi.bond.api.ReminderRoster
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.ReminderTime
import com.moyi.bond.infra.database.AnchorIntervalStore
import com.moyi.bond.infra.database.MemberNotifications
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.QuietHoursWindow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId

/**
 * [ReminderRoster] over the member rows and each bond's anchor timeline
 * (ADR-0045). Reads only; the push is asked for by `gratitude`, which knows
 * who has written.
 */
@Service
internal class ReminderRosterAdapter(
    private val members: MemberNotifications,
    private val anchorIntervals: AnchorIntervalStore,
) : ReminderRoster {
    @Transactional(readOnly = true)
    override fun remindersDueBetween(
        after: Instant,
        upTo: Instant,
    ): List<DueReminder> {
        val due =
            members.reminderCandidates().mapNotNull { candidate ->
                val zone = ZoneId.of(candidate.reminderTimezone)
                ReminderTime.occurrenceIn(candidate.reminderTimeLocal, zone, after, upTo)?.let { at -> Triple(candidate, zone, at) }
            }
        // One timeline per bond, read only for the bonds somebody is due in.
        val timelines = due.map { it.first.bondId }.distinct().associateWith { anchorIntervals.timelineOf(BondId(it)) }
        return due.map { (candidate, zone, at) ->
            val day = timelines.getValue(candidate.bondId).dayBoundsAt(at)
            DueReminder(
                bondId = candidate.bondId,
                memberId = candidate.memberId,
                userId = candidate.userId,
                remindAt = at,
                bondDate = day.date,
                bondDayEndsAt = day.endsAt,
                recipient =
                    PushRecipient(
                        zone = zone,
                        quietHours =
                            if (candidate.quietHoursStart != null && candidate.quietHoursEnd != null) {
                                QuietHoursWindow(candidate.quietHoursStart, candidate.quietHoursEnd)
                            } else {
                                null
                            },
                        preferences = candidate.preferences,
                    ),
            )
        }
    }
}
