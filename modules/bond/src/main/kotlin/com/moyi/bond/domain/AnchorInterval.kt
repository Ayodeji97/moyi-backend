package com.moyi.bond.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One span of wall-clock time over which a single IANA zone was **effective**
 * for a bond — which is not the same as the zone the bond currently requests.
 *
 * BR-6 and ADR-0030 require an approved anchor change to take effect from the
 * *next* Bond-day, never to recompute an existing one. B5 records the request
 * the moment consent completes; this records when the request starts deciding
 * dates. The two differ by up to one logical day, and that gap is the whole
 * reason this type exists: a zone string copied onto a day at lazy row
 * creation cannot defer anything, because on a day nobody has written to yet
 * there is no row to have copied it (spec §3.1).
 *
 * [effectiveTo] is null for exactly one interval per bond — the open one.
 * Intervals are contiguous: each one's [effectiveFrom] is the previous one's
 * [effectiveTo], with no gap and no overlap, which is what makes [AnchorTimeline]
 * total over every instant from the bond's creation onward.
 *
 * [firstLabel] is the first calendar label this interval issues — the date at
 * [effectiveFrom] in [zone]. With contiguity, it is what lets [AnchorTimeline]
 * answer "every label this bond has ever used" without reading `gratitude`'s
 * `bond_days`: the labels are exactly the run from the earliest interval's
 * [firstLabel] through the label in force now (doc 25 §6, ADR-0026 — no
 * dependency crosses the module boundary for this).
 */
internal data class AnchorInterval(
    val zone: ZoneId,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val firstLabel: LocalDate,
) {
    init {
        require(effectiveTo == null || effectiveTo.isAfter(effectiveFrom)) {
            "an anchor interval ends after it begins"
        }
    }

    fun contains(at: Instant): Boolean = !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo))
}

/** The UTC span of one Bond-day, half-open: `[startsAt, endsAt)`. */
internal data class DayBounds(
    val date: LocalDate,
    val startsAt: Instant,
    val endsAt: Instant,
) {
    init {
        require(!startsAt.isAfter(endsAt)) { "a bond-day ends no earlier than it begins" }
    }

    /**
     * True for a date the bond's own clock skipped entirely — an eastward
     * anchor move can clear a whole calendar label (doc 04 §8.5). The day
     * exists as a row so BR-4's run is not broken; no instant maps to it, so
     * its span is empty. **This is why the invariant above is `!isAfter` and
     * not `isBefore`** — the obvious `startsAt < endsAt` is wrong and fails on
     * the first eastward date-line test.
     */
    val isDegenerate: Boolean get() = startsAt == endsAt
}

/** Where a confirmed anchor change starts deciding dates, and what it costs. */
internal data class Handoff(
    val at: Instant,
    val firstLabel: LocalDate,
    val skippedLabels: List<LocalDate>,
)

/**
 * A bond's effective-zone history, ordered oldest first, contiguous, with
 * exactly one open interval last. Pure: it holds values and answers questions,
 * and the store is what loads it.
 *
 * Stays `internal` to `modules:bond` — a later task wraps this in a public
 * `bond.api` type for `gratitude` to consume; nothing outside this module
 * reaches it directly.
 */
internal class AnchorTimeline(
    val intervals: List<AnchorInterval>,
) {
    init {
        require(intervals.isNotEmpty()) { "a bond always has at least the interval it was created in" }
        require(intervals.last().effectiveTo == null) { "the last interval is the open one" }
        intervals.zipWithNext { earlier, later ->
            require(earlier.effectiveTo == later.effectiveFrom) {
                "anchor intervals are contiguous: ${earlier.effectiveTo} != ${later.effectiveFrom}"
            }
        }
    }

    fun zoneAt(at: Instant): ZoneId = intervalAt(at).zone

    fun dateAt(at: Instant): LocalDate = at.atZone(zoneAt(at)).toLocalDate()

    /**
     * The bounds of the logical day containing [at], **clipped to the interval
     * that decides it**. Clipping is what keeps days contiguous across a
     * handoff: the zone's own midnight before the handoff may lie inside the
     * previous interval, where a different zone was in charge.
     */
    fun dayBoundsAt(at: Instant): DayBounds {
        val interval = intervalAt(at)
        val zoned = at.atZone(interval.zone)
        val date = zoned.toLocalDate()
        val naturalStart = date.atStartOfDay(interval.zone).toInstant()
        val naturalEnd = date.plusDays(1).atStartOfDay(interval.zone).toInstant()
        val start = maxOf(naturalStart, interval.effectiveFrom)
        val end = interval.effectiveTo?.let { minOf(naturalEnd, it) } ?: naturalEnd
        return DayBounds(date = date, startsAt = start, endsAt = end)
    }

    /**
     * Every calendar label this bond has issued up to [now]. Contiguity is
     * what makes this a run rather than a query: intervals never overlap and
     * never gap, so the labels are exactly the dates from the first
     * interval's own first label through the label in force at [now].
     */
    fun usedLabelsUpTo(now: Instant): Set<LocalDate> {
        val first = intervals.first().firstLabel
        val last = dateAt(now)
        return generateSequence(first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(last) }
            .toSet()
    }

    /**
     * When a change confirmed at [now] starts deciding dates, and which
     * calendar labels it costs.
     *
     * The handoff is the end of the current logical day — never sooner, which
     * is BR-6's deferral. From there two things can go wrong and both are
     * handled here rather than by the caller:
     *
     * - **Eastward**, the new zone may already be past one or more labels by
     *   the handoff instant. Those never occur for this bond and come back in
     *   [Handoff.skippedLabels]; doc 04 §8.5 makes each one `FROZEN` so the
     *   run is not broken.
     * - **Westward**, the new zone's label at the handoff may be one the bond
     *   has already used. Opening it again would violate `bond_days`'s unique
     *   `(bond_id, date)`. The handoff is pushed to the next new-zone midnight
     *   until the label is unused, which extends the current day rather than
     *   duplicating a label (R3 in the plan; no compensating `FROZEN` day,
     *   because no label was lost).
     *
     * [usedLabels] defaults to [usedLabelsUpTo] at [now] — a caller only needs
     * to pass its own set when it wants to reason about labels this timeline
     * does not yet know about.
     */
    fun handoffFor(
        now: Instant,
        newZone: ZoneId,
        usedLabels: Set<LocalDate> = usedLabelsUpTo(now),
    ): Handoff {
        var at = dayBoundsAt(now).endsAt
        val previousLabel = dayBoundsAt(now).date
        var label = at.atZone(newZone).toLocalDate()
        while (label in usedLabels || !label.isAfter(previousLabel)) {
            at = label.plusDays(1).atStartOfDay(newZone).toInstant()
            label = at.atZone(newZone).toLocalDate()
        }
        // A date in this gap that is already in `usedLabels` was not skipped
        // in R3's sense — it already happened, under the zone effective
        // before this handoff, and is exactly why the handoff kept pushing
        // past it above. Only a date that never occurs for this bond at all
        // is genuinely skipped and needs `FROZEN` (doc 04 §8.5): counting an
        // already-used date here as well would double up the streak's
        // bookkeeping for a day that already has an entry, or a day already
        // frozen for some other reason.
        val skipped =
            generateSequence(previousLabel.plusDays(1)) { it.plusDays(1) }
                .takeWhile { it.isBefore(label) }
                .filterNot { it in usedLabels }
                .toList()
        return Handoff(at = at, firstLabel = label, skippedLabels = skipped)
    }

    /**
     * The interval deciding [at]. An instant strictly before the bond's own
     * first interval belongs to no interval at all — [error], naming the
     * instant, rather than silently falling back to the first one. Any other
     * instant is contained by exactly one interval (contiguity, checked in
     * `init`), including the open last interval, which contains everything
     * from its own [AnchorInterval.effectiveFrom] onward.
     */
    private fun intervalAt(at: Instant): AnchorInterval =
        intervals.lastOrNull { it.contains(at) }
            ?: error("an anchor timeline covers every instant from the bond's creation: $at")
}
