package com.moyi.gratitude.service

import com.moyi.gratitude.api.CloseResult
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.database.CloseCandidate
import com.moyi.gratitude.infra.database.CloseCandidates
import com.moyi.gratitude.service.CloseDay.Outcome
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * The sweep (spec §6.4): every day that may have ended, each handed to
 * [CloseDay] in a transaction of its own.
 *
 * **It remembers nothing between calls.** There is no watermark and no "last
 * run": what is left to do is whatever [CloseCandidates] still finds, so a
 * run that died halfway, a day that failed, and a job that did not run for a
 * week are all the same case to the next call.
 *
 * **The budget counts work, not looks.** A day that is examined and left —
 * waiting on a reveal time hours away, or found early by its stale end — is
 * a candidate on every run until it changes. Charged against the budget,
 * enough of those sorted ahead of the rest would use it up every time and
 * the days behind them would never close. So only a day closed, revealed or
 * failed is counted, and what ends a run with nothing to do is the paging
 * reaching the end of the candidates.
 *
 * **One day failing does not stop the rest.** A day that throws is logged by
 * id, counted in [CloseResult.failed] and left as it was; it is a candidate
 * again next time. Catching everything is deliberate here and nowhere else in
 * this module: the alternative is one bad row holding every couple's
 * midnight behind it.
 */
@Service
internal class CloseElapsedDays(
    private val candidates: CloseCandidates,
    private val closeDay: CloseDay,
    private val missingDays: CreateMissingDays,
) : DayCloser {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun closeElapsedDays(
        now: Instant,
        budget: Int,
    ): CloseResult {
        // A day is settled only once it has been over for a minute: see
        // `DayCloser.SETTLE_MARGIN`. A reveal falls due at `now` itself.
        val endedAsOf = now.minus(DayCloser.SETTLE_MARGIN)
        // Step 1 before step 2 (spec §6.4): a day written here is written
        // closed, so the sweep below has nothing more to do to it.
        val created = missingDays.create(now, endedAsOf, DayCloser.MAX_CREATED_PER_RUN)
        val tally = Tally()
        var previous: CloseCandidate? = null
        var page = candidates.after(previous, endedAsOf, PAGE)
        while (page.isNotEmpty() && tally.work < budget) {
            // Stops mid-page at the budget; `previous` is then the last day
            // actually looked at, so the check below sees what was not.
            for (candidate in page) {
                if (tally.work >= budget) break
                settle(candidate, now, endedAsOf, tally)
                previous = candidate
            }
            page = candidates.after(previous, endedAsOf, PAGE)
        }
        return CloseResult(
            created = created.days,
            closed = tally.closed,
            revealed = tally.revealed,
            failed = tally.failed + created.failed,
            bondsChanged = tally.bonds + created.bonds,
            backlog = page.isNotEmpty() || created.backlog,
        )
    }

    @Suppress("TooGenericExceptionCaught") // See the class KDoc: one day must not hold the others.
    private fun settle(
        candidate: CloseCandidate,
        now: Instant,
        endedAsOf: Instant,
        tally: Tally,
    ) {
        try {
            when (closeDay.settle(candidate.id, now, endedAsOf)) {
                Outcome.CLOSED -> tally.closed(candidate.bondId)
                Outcome.REVEALED -> tally.revealed(candidate.bondId)
                Outcome.NOT_YET, Outcome.ALREADY_CLOSED -> Unit
            }
        } catch (failure: Exception) {
            tally.failed++
            // The class and the ids. Never the message: a failure while
            // writing an entry can carry its row (`redacted` covers the one
            // kind known to; this line trusts none of them).
            log.error("close: day {} of bond {} failed with {}", candidate.id, candidate.bondId, failure.javaClass.simpleName)
        }
    }

    private class Tally {
        var closed = 0
        var revealed = 0
        var failed = 0
        val bonds = mutableSetOf<UUID>()

        /** What the budget is spent on: a day changed, or a day that failed. Looking at a day and leaving it is free. */
        val work get() = closed + revealed + failed

        fun closed(bondId: UUID) {
            closed++
            bonds += bondId
        }

        fun revealed(bondId: UUID) {
            revealed++
            bonds += bondId
        }
    }

    private companion object {
        /** Rows read per query. Small enough that a page is never a long-held result set. */
        const val PAGE = 200
    }
}
