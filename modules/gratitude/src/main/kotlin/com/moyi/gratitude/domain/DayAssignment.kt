package com.moyi.gratitude.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which Bond-day an entry belongs to (BR-3, BR-3a) — a pure function of the
 * four things the rule actually needs, rather than a method on an aggregate
 * that would have to carry a store to answer it.
 *
 * **BR-3, the ordinary case.** A Bond-day is a date in the bond's own
 * [zone] — its anchor `RegionZone` (doc 04 §6) — never the writer's. Two
 * members in different time zones write into the same calendar, the one the
 * bond is anchored to — so [submittedAt] converted through [zone] is the
 * fallback every path below either returns or falls back to.
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
 * - **Landing on a day [isClosed] reports true for** falls back rather than
 *   writing into a day that cannot be revisited. BR-10 makes a closed day's
 *   status authoritative, and BR-1 grants read access only on `REVEALED` or
 *   a closed `SOLO` day — an entry back-filled onto a closed `EMPTY` day
 *   would be readable by neither member and releasable by no transition.
 *   Falling back to today is BR-3a's answer to "what happens to the words":
 *   they are kept, on a day that can still hold them, rather than silently
 *   dropped.
 *
 * Any one of those three sends the claim back to the [submittedAt] fallback;
 * none of them is an error the caller has to handle; a rejected `intendedAt`
 * degrades to "filed today" rather than refusing the write.
 *
 * **[isClosed] is a lambda, not a store, on purpose.** The rule above is
 * everything BR-3 and BR-3a say, and it has no dependency of its own on a
 * database — so it stays a pure function a unit test can drive through every
 * branch with nothing but values. The service is what has a day store, and
 * it supplies the lookup: `{ date -> days.statusOf(bondId, date)?.isClosed
 * == true }`.
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
 */
internal object DayAssignment {
    private const val CLOCK_SKEW_MINUTES = 5L
    private const val OFFLINE_WINDOW_HOURS = 36L
    private val CLOCK_SKEW: Duration = Duration.ofMinutes(CLOCK_SKEW_MINUTES)
    private val OFFLINE_WINDOW: Duration = Duration.ofHours(OFFLINE_WINDOW_HOURS)

    /** [resolve]'s answer: the day an entry lands on, and the instant trusted as when it was intended. See [resolve]'s own KDoc. */
    data class Resolution(
        val date: LocalDate,
        val resolvedAt: Instant,
    )

    /**
     * [dateFor]'s own answer, plus the instant that produced it — see the
     * class KDoc's F1 note. A pure function of the same four inputs, so the
     * date this returns and the date [dateFor] returns can never disagree:
     * [dateFor] is defined in terms of this one, not the other way round.
     */
    fun resolve(
        submittedAt: Instant,
        intendedAt: Instant?,
        zone: ZoneId,
        isClosed: (LocalDate) -> Boolean,
    ): Resolution {
        val fallback = submittedAt.atZone(zone).toLocalDate()
        val acceptedDate =
            intendedAt
                ?.takeIf { !it.isAfter(submittedAt.plus(CLOCK_SKEW)) }
                ?.takeIf { !it.isBefore(submittedAt.minus(OFFLINE_WINDOW)) }
                ?.let { it.atZone(zone).toLocalDate() }
                ?.takeUnless(isClosed)
        return if (acceptedDate != null) {
            Resolution(date = acceptedDate, resolvedAt = checkNotNull(intendedAt))
        } else {
            Resolution(date = fallback, resolvedAt = submittedAt)
        }
    }

    fun dateFor(
        submittedAt: Instant,
        intendedAt: Instant?,
        zone: ZoneId,
        isClosed: (LocalDate) -> Boolean,
    ): LocalDate = resolve(submittedAt, intendedAt, zone, isClosed).date
}
