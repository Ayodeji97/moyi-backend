package com.moyi.bond.service

import com.moyi.bond.api.DueReminder
import com.moyi.bond.api.ReminderRoster
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.ReminderTime
import com.moyi.bond.infra.database.AnchorIntervalStore
import com.moyi.bond.infra.database.BondClosingStore
import com.moyi.bond.infra.database.MemberNotifications
import com.moyi.bond.infra.database.ReminderCandidate
import com.moyi.identity.api.AccountStanding
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.QuietHoursWindow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalTime
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
    private val standing: AccountStanding,
    private val pause: BondPause,
    private val bonds: BondClosingStore,
) : ReminderRoster {
    @Transactional(readOnly = true)
    override fun remindersDueBetween(
        after: Instant,
        upTo: Instant,
    ): List<DueReminder> = dueBetween(unpaused(members.reminderCandidates()), ReminderCandidate::reminderTimeLocal, after, upTo)

    /**
     * Of [candidates], those in a bond that is not paused (A3): a paused bond's members get no
     * reminder, because they cannot write. **Only the reminder asks this.** [dueBetween] is
     * shared with the on-this-day push, which the pause does not stop.
     *
     * Whether a bond is paused is [BondPause]'s answer, the one rule ([com.moyi.bond.domain.Bond.isPausedFor]),
     * never restated here. This only narrows which bonds are asked: a bond none of whose
     * candidates is suspended cannot be paused, so on a run with no suspended member (the usual
     * one) no bond is loaded, and otherwise only the few bonds a suspended member is in.
     */
    private fun unpaused(candidates: List<ReminderCandidate>): List<ReminderCandidate> {
        val suspended = standing.suspendedAmong(candidates.map { it.userId }.distinct())
        if (suspended.isEmpty()) return candidates
        val asked = candidates.filter { it.userId in suspended }.map { it.bondId }.distinct()
        val paused = pause.pausedAmong(bonds.findAll(asked)).filterValues { it }.keys
        return candidates.filter { it.bondId !in paused }
    }

    /**
     * Each of [candidates] whose [timeOf], in their reminder zone, fell within
     * `(after, upTo]`, with the bond-day it fell in and their settings: the
     * daily reminder's rule, and the on-this-day push's (ADR-0046), which
     * asks it with a fixed time of day instead of the member's own.
     */
    fun dueBetween(
        candidates: List<ReminderCandidate>,
        timeOf: (ReminderCandidate) -> LocalTime,
        after: Instant,
        upTo: Instant,
    ): List<DueReminder> {
        val due =
            candidates.mapNotNull { candidate ->
                val zone = ZoneId.of(candidate.reminderTimezone)
                ReminderTime.occurrenceIn(timeOf(candidate), zone, after, upTo)?.let { at -> Triple(candidate, zone, at) }
            }
        // One timeline per bond, read only for the bonds somebody is due in.
        val timelines = due.map { it.first.bondId }.distinct().associateWith { anchorIntervals.timelineOf(BondId(it)) }
        // A reminder that fell before its bond began has no bond-day to be about: a bond made at
        // 23:57 whose member's reminder is 23:55. Asked of the timeline, that instant throws, and
        // took every other member's reminder down with it (found by the smoke run).
        return due
            .filter { (candidate, _, at) ->
                !at.isBefore(
                    timelines
                        .getValue(candidate.bondId)
                        .intervals
                        .first()
                        .effectiveFrom,
                )
            }.map { (candidate, _, at) ->
                val day = timelines.getValue(candidate.bondId).dayBoundsAt(at)
                DueReminder(
                    bondId = candidate.bondId,
                    memberId = candidate.memberId,
                    userId = candidate.userId,
                    remindAt = at,
                    bondDate = day.date,
                    bondDayEndsAt = day.endsAt,
                    recipient = candidate.recipient(),
                )
            }
    }
}

/**
 * A member's settings in one bond as a push carries them (ADR-0045): their
 * reminder zone, their quiet hours if they set both ends, their toggles. The
 * one place a member row becomes a [PushRecipient].
 */
internal fun ReminderCandidate.recipient(): PushRecipient =
    PushRecipient(
        zone = ZoneId.of(reminderTimezone),
        quietHours =
            if (quietHoursStart != null && quietHoursEnd != null) {
                QuietHoursWindow(quietHoursStart, quietHoursEnd)
            } else {
                null
            },
        preferences = preferences,
    )
