package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondClosingView
import com.moyi.common.core.IdGenerator
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayWindow
import com.moyi.gratitude.infra.database.MissingDays
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
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
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** What one call wrote: how many days, for which bonds, and whether it stopped at [budget] with more to write. */
    data class Created(
        val days: Int,
        val bonds: Set<UUID>,
        val backlog: Boolean,
        /** Bonds whose days could not be worked out or written, and were left for the next run. */
        val failed: Int,
    )

    /**
     * @param now the caller's reading of the time
     * @param endedAsOf only a day that had ended by this instant is written (`DayCloser.SETTLE_MARGIN`)
     */
    fun create(
        now: Instant,
        endedAsOf: Instant,
        budget: Int,
    ): Created {
        val tally = Tally()
        var after: UUID? = null
        var page = access.bondsToSweep(after, BOND_PAGE)
        while (page.isNotEmpty() && !tally.backlog) {
            for (bondId in page) {
                fill(bondId, now, endedAsOf, budget, tally)
                if (tally.backlog) break
            }
            after = page.last()
            page = access.bondsToSweep(after, BOND_PAGE)
        }
        return Created(tally.written, tally.bonds.toSet(), tally.backlog, tally.failed)
    }

    private class Tally {
        var written = 0
        var failed = 0
        var backlog = false
        val bonds = mutableSetOf<UUID>()
    }

    /**
     * One bond, and whatever goes wrong with it stays with it. This runs
     * before the sweep, for every bond, on every run: one bond whose
     * calendar cannot be read would otherwise stop every couple's days from
     * closing, every quarter of an hour, until somebody noticed.
     */
    @Suppress("TooGenericExceptionCaught") // As `CloseElapsedDays.settle`: one bond must not hold the others.
    private fun fill(
        bondId: UUID,
        now: Instant,
        endedAsOf: Instant,
        budget: Int,
        tally: Tally,
    ) {
        try {
            val gaps = gapsOf(bondId, now, endedAsOf)
            val room = budget - tally.written
            val count = write(bondId, gaps.take(room), now)
            if (count > 0) tally.bonds += bondId
            tally.written += count
            // Only once its days are written: a bond that fails with more
            // days than the budget must not end the run for the bonds after it.
            if (gaps.size > room) tally.backlog = true
        } catch (failure: Exception) {
            tally.failed++
            log.error("close: the missing days of bond {} could not be written: {}", bondId, failure.javaClass.simpleName)
        }
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
                    // When they were written: the clock inside the transaction,
                    // never earlier than the run's own `now`, which may by
                    // then be minutes old.
                    val at = maxOf(now, clock.instant()).truncatedTo(ChronoUnit.MICROS)
                    gaps.count { gap ->
                        val id = ids.timeOrdered()
                        missing.insertClosed(id, bondId, gap.window, gap.zone, gap.status, at).also { inserted ->
                            // As `CloseDay`: a suspended day is settled without an announcement.
                            if (inserted && gap.status != BondDayStatus.SUSPENDED) {
                                events.publish(
                                    OutboxEvent("BondDay", id, "DayClosed", mapOf("bondId" to bondId), at),
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
        endedAsOf: Instant,
    ): List<Gap> {
        val view = access.closingViewOf(bondId)
        val activeSince = view?.activeSince ?: return emptyList()
        val have = missing.datesOf(bondId)
        return windowsOf(view, activeSince, now, endedAsOf).filter { it.window.date !in have }.toList()
    }

    /**
     * Every window of [view]'s calendar that had ended by [endedAsOf] and
     * began before the bond ended, and every label the calendar stepped over
     * on the way — including one stepped over by a window that has begun and
     * not yet ended: the label was skipped the moment the calendar moved
     * past it, and a streak walked that evening must already find it.
     */
    private fun windowsOf(
        view: BondClosingView,
        activeSince: Instant,
        now: Instant,
        endedAsOf: Instant,
    ): Sequence<Gap> =
        sequence {
            val timeline = view.anchorTimeline
            val until = view.endedAt?.takeIf { it.isBefore(now) } ?: now
            var at = activeSince
            var previous: DayWindow? = null
            while (true) {
                val bounds = timeline.dayBoundsAt(at)
                val window = DayWindow(bounds.date, bounds.startsAt, bounds.endsAt)
                val zone = timeline.zoneIdAt(bounds.startsAt)
                // Began before the bond stopped taking writes; a window that
                // did not is not this bond's day at all.
                val began = bounds.startsAt.isBefore(until)
                // A jump in the labels: dates an eastward change stepped
                // over. They have no instants, so each is a day of no length
                // at the moment the calendar moved past it.
                // (The window that follows an ended one starts where that one
                // ended, so by here the calendar has already moved past them.)
                if (began && previous != null) {
                    generateSequence(previous.date.plusDays(1)) { it.plusDays(1) }
                        .takeWhile { it.isBefore(window.date) }
                        .forEach { skipped -> yield(Gap(DayWindow(skipped, window.startsAt, window.startsAt), zone, BondDayStatus.FROZEN)) }
                }
                if (!began || bounds.endsAt.isAfter(endedAsOf)) break
                // Nobody wrote; and if the bond was refusing writes for any of
                // it, nobody was let down either (ADR-0034, the owner's ruling).
                val paused = view.wasPausedDuring(window.startsAt, window.endsAt)
                yield(Gap(window, zone, if (paused) BondDayStatus.SUSPENDED else BondDayStatus.EMPTY))
                check(bounds.endsAt.isAfter(at)) { "a bond's calendar must move forward: ${bounds.date} ends at ${bounds.endsAt}" }
                previous = window
                at = bounds.endsAt
            }
        }

    private companion object {
        const val BOND_PAGE = 200
    }
}
