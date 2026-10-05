package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondClosingView
import com.moyi.common.core.IdGenerator
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayWindow
import com.moyi.gratitude.infra.database.MissingDays
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Writes the days nobody opened (spec §6.4 step 1). A Bond-day's row is
 * created lazily, by the first entry written on it; a day neither member
 * wrote on has no row, and without one it cannot be `EMPTY`, cannot break a
 * streak, and does not exist for the archive.
 *
 * **From the bond's activation, through its timeline, one window at a
 * time.** Each window is asked of the timeline at the instant the last one
 * ended, so a day lengthened by a westward change is one long window and a
 * label an eastward change skipped is simply never returned — it shows up as
 * a jump in the dates, and is written `FROZEN` without spending a freeze
 * (ADR-0031 decision 14). Nothing is derived from a zone's own midnight.
 *
 * **Not from the greatest existing date.** A row opened today, by today's
 * first entry, says nothing about the week before it. Every window from
 * activation is checked against the dates the bond has.
 *
 * **Never before the pairing, never after the end.** Doc 04 §8.3a creates no
 * day for a bond that is one person. A bond that has ended gets the days up
 * to the one it ended on, and none after.
 *
 * What this costs: every bond's dates are read on every run. That is a
 * query per bond per quarter-hour, which is nothing at the size this runs
 * at and is the first thing to replace when it is not — with a watermark
 * that only advances past dates verified settled (spec §6.4).
 */
@Service
internal class CreateMissingDays(
    private val access: BondAccess,
    private val missing: MissingDays,
    private val events: EventPublisher,
    private val ids: IdGenerator,
    private val transactions: TransactionTemplate,
) {
    /** What one call wrote: how many days, for which bonds, and whether it stopped at [budget] with more to write. */
    data class Created(
        val days: Int,
        val bonds: Set<UUID>,
        val backlog: Boolean,
    )

    fun create(
        now: Instant,
        budget: Int,
    ): Created {
        var written = 0
        var backlog = false
        val bonds = mutableSetOf<UUID>()
        var after: UUID? = null
        var page = access.bondsToSweep(after, BOND_PAGE)
        while (page.isNotEmpty() && !backlog) {
            for (bondId in page) {
                val gaps = gapsOf(bondId, now)
                val room = budget - written
                if (gaps.size > room) backlog = true
                val count = write(bondId, gaps.take(room), now)
                if (count > 0) bonds += bondId
                written += count
                if (backlog) break
            }
            after = page.last()
            page = access.bondsToSweep(after, BOND_PAGE)
        }
        return Created(written, bonds, backlog)
    }

    private data class Gap(
        val window: DayWindow,
        val zone: String,
        val status: BondDayStatus,
    )

    /** One bond's days in one transaction: its batch commits whole, and the next run resumes from what is still missing. */
    private fun write(
        bondId: UUID,
        gaps: List<Gap>,
        now: Instant,
    ): Int =
        if (gaps.isEmpty()) {
            0
        } else {
            checkNotNull(
                transactions.execute {
                    gaps.count { gap ->
                        val id = ids.timeOrdered()
                        missing.insertClosed(id, bondId, gap.window, gap.zone, gap.status, now).also { inserted ->
                            if (inserted) {
                                events.publish(
                                    OutboxEvent("BondDay", id, "DayClosed", mapOf("bondId" to bondId), now.truncatedTo(ChronoUnit.MICROS)),
                                )
                            }
                        }
                    }
                },
            )
        }

    private fun gapsOf(
        bondId: UUID,
        now: Instant,
    ): List<Gap> {
        val view = access.closingViewOf(bondId)
        val activeSince = view?.activeSince ?: return emptyList()
        val have = missing.datesOf(bondId)
        return windowsOf(view, activeSince, now).filter { it.window.date !in have }.toList()
    }

    /** Every window of [view]'s calendar that has ended by [now] and began before the bond ended, with the labels skipped between them. */
    private fun windowsOf(
        view: BondClosingView,
        activeSince: Instant,
        now: Instant,
    ): Sequence<Gap> =
        sequence {
            val timeline = view.anchorTimeline
            val until = view.endedAt?.takeIf { it.isBefore(now) } ?: now
            var at = activeSince
            var previous: DayWindow? = null
            while (true) {
                val bounds = timeline.dayBoundsAt(at)
                if (bounds.endsAt.isAfter(now) || !bounds.startsAt.isBefore(until)) break
                val window = DayWindow(bounds.date, bounds.startsAt, bounds.endsAt)
                val zone = timeline.zoneIdAt(bounds.startsAt)
                // A jump in the labels: dates an eastward change stepped
                // over. They have no instants, so each is a day of no length
                // at the moment the calendar moved past it.
                previous?.let { before ->
                    generateSequence(before.date.plusDays(1)) { it.plusDays(1) }
                        .takeWhile { it.isBefore(window.date) }
                        .forEach { skipped -> yield(Gap(DayWindow(skipped, window.startsAt, window.startsAt), zone, BondDayStatus.FROZEN)) }
                }
                yield(Gap(window, zone, BondDayStatus.EMPTY))
                check(bounds.endsAt.isAfter(at)) { "a bond's calendar must move forward: ${bounds.date} ends at ${bounds.endsAt}" }
                previous = window
                at = bounds.endsAt
            }
        }

    private companion object {
        const val BOND_PAGE = 200
    }
}
