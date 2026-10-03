package com.moyi.gratitude.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Which Bond-day an entry belongs to (BR-3, BR-3a) — a pure function of the
 * four things the rule actually needs, rather than a method on an aggregate
 * that would have to carry a store to answer it.
 *
 * **BR-3, the ordinary case.** A Bond-day is a day on the bond's own
 * calendar — the zone that is *effective* for the bond at that instant (doc
 * 04 §6, BR-6), never the writer's and never merely the zone the bond
 * currently requests. Two members in different time zones write into the
 * same calendar, so the day [BondCalendar.dayAt] gives for [submittedAt] is
 * the fallback every path below either returns or falls back to.
 *
 * **The calendar's day is taken whole, never recomputed.** [Resolution.bounds]
 * is exactly the [DayWindow] the calendar returned — the ~48-hour day a
 * westward anchor change merges (plan R3), the 11-hour one an eastward change
 * clips, the empty one it skips. Nothing here takes a midnight in some zone;
 * a `ZoneId` alone cannot express any of those days, which is why this takes
 * a [BondCalendar] rather than one.
 *
 * **BR-3a, the offline-draft case.** A client may have composed an entry
 * before it could send it — a flight, a dead connection — and it says so by
 * sending [intendedAt] alongside [submittedAt]. That claim is trusted only
 * within limits, each guarding a different way it could be wrong:
 *
 * - **Ahead of now by more than [CLOCK_SKEW] (five minutes)** is treated as
 *   a clock that cannot be trusted rather than a real future date — there is
 *   no such thing as tomorrow's gratitude, and the margin exists only to
 *   absorb ordinary clock drift between a client and this service.
 * - **Older than [OFFLINE_WINDOW] (thirty-six hours)** is treated as too
 *   stale to back-file — chosen to comfortably cover a day spent offline
 *   without opening a window wide enough to rewrite last month's streak.
 * - **Landing on a day [isSettled] reports true for** falls back rather than
 *   writing into a day that cannot be revisited. BR-10 makes a closed day's
 *   status authoritative, and BR-1 grants read access only on `REVEALED` or
 *   a closed `SOLO` day — an entry back-filled onto a closed `EMPTY` day
 *   would be readable by neither member and releasable by no transition.
 *   Falling back to today is BR-3a's answer to "what happens to the words":
 *   they are kept, on a day that can still hold them, rather than silently
 *   dropped.
 *
 * - **Before the bond's calendar begins** ([BondCalendar.dayAt] answers
 *   `null`) is a claim about a time the bond did not exist. Filing it would
 *   open a day before the bond's first label, which nothing downstream — the
 *   used-label run, the close job, the streak walk — can account for.
 *
 * Any one of those four sends the claim back to the [submittedAt] fallback;
 * none of them is an error the caller has to handle; a rejected `intendedAt`
 * degrades to "filed today" rather than refusing the write.
 *
 * **[isSettled] is a lambda, not a store, on purpose.** The rule above is
 * everything BR-3 and BR-3a say, and it has no dependency of its own on a
 * database — so it stays a pure function a unit test can drive through every
 * branch with nothing but values. The service is what has a day store, and
 * it supplies the lookup: `{ date -> days.findByBondAndDate(bondId,
 * date)?.isSettled == true }`. "Settled" is spec §6.1.2's word:
 * `closedAt != null`, including `FROZEN` and an elapsed `SUSPENDED` — and
 * [BondDay.isSettled] is the one place that says so, asked here before the
 * day is locked and by `SubmitEntry` again once it is (§6.1.3).
 *
 * **F1 (whole-branch review): [resolve] is what [Entry.submit] must take its
 * `intendedAt` from, never the raw client claim.** `entries.intended_at`'s
 * own column comment and [Entry.submit]'s own KDoc both describe that value
 * as "BR-3/BR-3a's resolved day" — a claim that was false before this fix:
 * `SubmitEntry` persisted `draft.intendedAt ?: now` verbatim, so a client
 * could send `intendedAt: 2099-01-01` and have it stored and echoed back
 * even though [dateFor] rejected it for *date* purposes and fell back to
 * today. [Resolution.resolvedAt] is the instant the trust checks actually
 * accepted — [intendedAt] itself when every check in [dateFor] passed,
 * [submittedAt] otherwise — so a rejected claim can never reach the archive.
 * [Resolution.usedIntendedAt] says which of the two it was (ruling P4): a
 * back-fill and a live write are different facts to the caller (spec §6.1.3).
 */
internal object DayAssignment {
    private const val CLOCK_SKEW_MINUTES = 5L
    private const val OFFLINE_WINDOW_HOURS = 36L
    private val CLOCK_SKEW: Duration = Duration.ofMinutes(CLOCK_SKEW_MINUTES)
    private val OFFLINE_WINDOW: Duration = Duration.ofHours(OFFLINE_WINDOW_HOURS)

    /**
     * [resolve]'s answer: the day an entry lands on, the instant trusted as
     * when it was intended, the span of that day exactly as the calendar drew
     * it, and whether the client's `intendedAt` was the instant used. See
     * [resolve]'s own KDoc.
     */
    data class Resolution(
        val date: LocalDate,
        val resolvedAt: Instant,
        val bounds: DayWindow,
        val usedIntendedAt: Boolean,
    )

    /**
     * [dateFor]'s own answer, plus the instant and the window that produced
     * it — see the class KDoc's F1 note. A pure function of the same four
     * inputs, so the date this returns and the date [dateFor] returns can
     * never disagree: [dateFor] is defined in terms of this one, not the
     * other way round.
     */
    fun resolve(
        submittedAt: Instant,
        intendedAt: Instant?,
        calendar: BondCalendar,
        isSettled: (LocalDate) -> Boolean,
    ): Resolution {
        val accepted =
            intendedAt
                ?.takeIf { !it.isAfter(submittedAt.plus(CLOCK_SKEW)) }
                ?.takeIf { !it.isBefore(submittedAt.minus(OFFLINE_WINDOW)) }
                ?.let(calendar::dayAt)
                ?.takeUnless { isSettled(it.date) }
        return if (accepted != null) {
            Resolution(date = accepted.date, resolvedAt = checkNotNull(intendedAt), bounds = accepted, usedIntendedAt = true)
        } else {
            val today = checkNotNull(calendar.dayAt(submittedAt)) { "a bond's calendar covers every submission instant: $submittedAt" }
            Resolution(date = today.date, resolvedAt = submittedAt, bounds = today, usedIntendedAt = false)
        }
    }

    fun dateFor(
        submittedAt: Instant,
        intendedAt: Instant?,
        calendar: BondCalendar,
        isSettled: (LocalDate) -> Boolean,
    ): LocalDate = resolve(submittedAt, intendedAt, calendar, isSettled).date
}
