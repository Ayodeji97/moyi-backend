package com.moyi.gratitude.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Doc 04 §3's state machine for a Bond-day, all **eight** values — even
 * though later slices own closing and streak settlement.
 *
 * Doc 07's own DDL lists five and is stale (Phase 3 design §12.1): it is
 * missing [PENDING_REVEAL] (FR-062, the window between both entries arriving
 * and the reveal job running), [SUSPENDED] (doc 04 §8.1–§8.3a, a
 * `PENDING_MEMBER` bond's own day) and [FROZEN] (BR-5, doc 04 §8.5, a
 * streak-preserving freeze token). The enum and V12's `CHECK` are written
 * once, from doc 04, not from doc 07 — the same call V9 made for `bonds`'
 * own status column.
 *
 * **[isClosed] lives here, not on [BondDay] alone; [BondDay.isClosed]
 * delegates to it.** A caller holding only a status — one read straight off
 * a row, without the aggregate — can still ask. Putting the answer only on
 * [BondDay] would have given that caller nothing to ask, and putting it in
 * two places would have given the codebase two chances to disagree with
 * itself about what "closed" means.
 *
 * **"Closed" is a fact about the status; BR-3a's "settled" is not**, and is
 * [BondDay.isSettled]: it needs `closedAt`, which no status carries.
 */
internal enum class BondDayStatus(
    val isClosed: Boolean,
) {
    OPEN(isClosed = false),
    PARTIAL(isClosed = false),
    PENDING_REVEAL(isClosed = false),
    REVEALED(isClosed = true),
    SOLO(isClosed = true),
    EMPTY(isClosed = true),

    /**
     * **Not closed — and that is the subtle one.** Nothing closed this day;
     * doc 04 §8.3a excludes it from evaluation entirely while its bond waits
     * for a second member, which is a different thing from having been
     * closed. BR-3a's fallback, the eventual close job (C3) and the streak
     * walk all have to tell the two apart: a closed day is a record nothing
     * may write into again, a suspended one is a day nobody is being asked
     * about yet. `isClosed == false` here is what lets a caller that only
     * checks [isClosed] correctly leave a suspended day alone rather than
     * treating "not open to further writes" as the same question.
     */
    SUSPENDED(isClosed = false),
    FROZEN(isClosed = true),
}

/**
 * The aggregate root for one bond's one calendar date (doc 04 §3, spec
 * §3.1/§12.5) — the row an [Entry] hangs off, never the entry itself, and
 * never holding a list of them: `entries.bond_day_id` is a plain column with
 * a database-level `REFERENCES`, not an object graph (V12, ADR-0026).
 *
 * Immutable, as [com.moyi.bond.domain.Bond] is: a transition is a method
 * returning a new [BondDay], and the invariants this row carries are
 * `require`d in the constructor so an object that breaks one cannot exist.
 *
 * **[startsAt]/[endsAt] are the day; [anchorTimezone] only remembers it.**
 * BR-6 and ADR-0030 require a zone change to never recompute an existing
 * day, and spec §3.1 goes further: the span is resolved against the bond's
 * effective-zone timeline when the day opens, and *persisted*. One thing
 * about it may change afterwards, and only one: a westward anchor change can
 * make an unsettled day run on to its successor's start (plan R3), and
 * [extendedTo] moves [endsAt] later to match (ruling P10). [startsAt] and
 * [anchorTimezone] never change, and a settled day's span never does. A zone id alone cannot
 * describe a day an anchor change clipped, merged (westward, plan R3: its length depends on the zone pair)
 * or skipped (an empty span, eastward) — so [DayWindow]'s `[startsAt,
 * endsAt)` is the authority on which instants belong here, and
 * [anchorTimezone] is the snapshot of the zone in force when the day began,
 * kept for display and audit. [open] and [openSuspended] both stamp exactly
 * what they were called with; nothing on this aggregate ever reads either
 * back off the bond.
 *
 * Counting an accepted entry ([withEntry]) is separate from evaluating the
 * reveal ([revealWhenDue]). The service persists that transition, both entry
 * timestamps and the outbox event in one transaction under this day's lock.
 */
internal data class BondDay(
    val id: BondDayId,
    val bondId: UUID,
    val date: LocalDate,
    val status: BondDayStatus,
    val anchorTimezone: ZoneId,
    /** The first instant of this day, inclusive — see the class KDoc. */
    val startsAt: Instant,
    /** The first instant after this day, exclusive. Equal to [startsAt] for a label an eastward change skipped. */
    val endsAt: Instant,
    val entryCount: Int,
    val revealedAt: Instant?,
    val closedAt: Instant?,
    val createdAt: Instant,
    /**
     * The row version behind the `ETag` (doc 06 §1), as [com.moyi.bond.domain.Bond.version] is. Hibernate's
     * `@Version`: `BondDayStore.update` bumps it on every change — an entry arriving, an extended [endsAt].
     * No endpoint exposes it as an `ETag` yet.
     */
    val version: Int,
) {
    init {
        // Mirrors V12's bond_days_entry_count_check, for the reason Bond.kt's
        // own mirrors give: the database constraint cannot be bypassed, this
        // one can explain itself.
        require(entryCount in 0..MAX_ENTRIES) { "a bond-day holds at most $MAX_ENTRIES entries" }
        // V12's bond_days_span_check. `!isAfter`, not `isBefore`: a skipped
        // date's day is empty, and must still be representable (DayWindow).
        require(!startsAt.isAfter(endsAt)) { "a bond-day's span may be empty but never inverted" }
    }

    /** [BondDayStatus.isClosed], on the aggregate for a caller who has already loaded the whole thing. */
    val isClosed: Boolean get() = status.isClosed

    /**
     * BR-3a's "already settled" (spec §6.1.2): nothing may be filed on this
     * day any more. `closedAt != null` is the spec's own test and covers what
     * the status cannot — an **elapsed `SUSPENDED`** day keeps its status when
     * the close job stamps it (§6.4), and [BondDayStatus.SUSPENDED] is not a
     * closed status. `status.isClosed` covers the other half of that
     * sentence, a day "already `REVEALED` before midnight": revealed by the
     * second submission, and not yet stamped by a close.
     */
    val isSettled: Boolean get() = closedAt != null || status.isClosed

    /**
     * Counts the insert BR-2 accepted. Suspended days remain excluded until
     * [resumeJoiningDay] proves activation occurred in their span. The caller
     * follows this with [revealWhenDue] in the same locked transaction: a
     * two-entry PARTIAL value is only an intermediate calculation, never a
     * committed status.
     */
    fun withEntry(): BondDay =
        copy(
            entryCount = entryCount + 1,
            status = if (status == BondDayStatus.SUSPENDED) BondDayStatus.SUSPENDED else BondDayStatus.PARTIAL,
        )

    /** A pre-reveal deletion changes the live count; settled history is authoritative (BR-10). */
    fun withoutEntry(): BondDay =
        if (isSettled) {
            this
        } else {
            copy(
                entryCount = entryCount - 1,
                status =
                    when {
                        status == BondDayStatus.SUSPENDED -> status
                        entryCount == 1 -> BondDayStatus.OPEN
                        else -> BondDayStatus.PARTIAL
                    },
            )
        }

    /**
     * FR-062: two live entries wait in their own status until the configured
     * local time on this day's snapshot calendar. Java's zone resolution moves
     * a gap forward and chooses the earlier offset in an overlap. Comparing
     * instants also handles an elapsed joining day and a westward extended day.
     * The caller holds the day lock and persists both entries in this transaction.
     */
    fun revealWhenDue(
        revealTime: LocalTime?,
        now: Instant,
    ): BondDay {
        if (entryCount != MAX_ENTRIES || status !in setOf(BondDayStatus.PARTIAL, BondDayStatus.PENDING_REVEAL)) return this
        val due = revealTime?.let { date.atTime(it).atZone(anchorTimezone).toInstant() }
        return if (due != null && now.isBefore(due)) {
            copy(status = BondDayStatus.PENDING_REVEAL)
        } else {
            copy(status = BondDayStatus.REVEALED, revealedAt = revealedAt ?: now.truncatedTo(ChronoUnit.MICROS))
        }
    }

    /**
     * Spec §12.4: only the suspended day containing the recorded activation
     * resumes. Older private days stay private; elapsed joining days still
     * resume. The caller reconciles the timeline span before asking this rule.
     */
    fun resumeJoiningDay(activeSince: Instant?): BondDay {
        val joining = activeSince != null && !activeSince.isBefore(startsAt) && activeSince.isBefore(endsAt)
        // A closed day is a record (BR-10). The closer reconciles a joining
        // day BEFORE it stamps it, under the same lock; resumed afterwards, a
        // one-entry day would be PARTIAL and closed at once, which nothing settles.
        return if (status == BondDayStatus.SUSPENDED && joining && closedAt == null) {
            copy(status = if (entryCount == 0) BondDayStatus.OPEN else BondDayStatus.PARTIAL)
        } else {
            this
        }
    }

    /**
     * The end of the day (spec §6.4 step 2, FR-063): what it is once nobody
     * can write on it any more.
     *
     * - `OPEN` becomes `EMPTY`, and `PARTIAL` becomes `SOLO`. On a `SOLO` day
     *   the caller reveals the lone entry; [revealedAt] here stays unset,
     *   because it records the two being read together, which did not happen.
     * - `PENDING_REVEAL` becomes `REVEALED`: a reveal time later than the
     *   day's own end cannot hold both entries back past it.
     * - A day revealed while it was open, and a `SUSPENDED` day, gain
     *   [closedAt] and nothing else. A suspended day is excluded from
     *   evaluation (doc 04 §8.3a), so closing it reveals nothing and counts
     *   for nothing; it is closed so that it is not swept again.
     *
     * **The caller proves the day has ended, by the bond's timeline** — after
     * [extendedTo], never from a stored [endsAt] taken on trust — and has
     * already applied [resumeJoiningDay] and [revealWhenDue], under the day's
     * lock. Idempotent: a closed day is returned as it is.
     */
    fun close(now: Instant): BondDay {
        if (closedAt != null) return this
        require(!now.isBefore(endsAt)) { "a bond-day cannot be closed before it has ended" }
        check(
            status != BondDayStatus.PARTIAL || entryCount == 1,
        ) { "a two-entry day is revealed or pending, never PARTIAL, by the time it closes" }
        val at = now.truncatedTo(ChronoUnit.MICROS)
        return when (status) {
            BondDayStatus.OPEN -> copy(status = BondDayStatus.EMPTY, closedAt = at)
            BondDayStatus.PARTIAL -> copy(status = BondDayStatus.SOLO, closedAt = at)
            BondDayStatus.PENDING_REVEAL -> copy(status = BondDayStatus.REVEALED, revealedAt = revealedAt ?: at, closedAt = at)
            else -> copy(closedAt = at)
        }
    }

    /**
     * This day with its span brought up to what the bond's calendar now says
     * about it (ruling P10) — which can only mean **ending later**.
     *
     * A westward anchor change agreed after this day's row was opened merges
     * the day into its successor's start (plan R3): the label is kept and the
     * day runs on: 25 hours for a one-hour move, 49 for Kiritimati to Pago Pago. The row was written with the span the
     * calendar gave *then*; [window] is what the calendar gives *now*. Without
     * this, an entry could be filed on a row whose stored span does not
     * contain it, and the stored days would leave a hole before the next one
     * (spec §3.1: "intervals remain contiguous and non-overlapping").
     *
     * - **Extend-only.** [endsAt] moves later or not at all; a [window] that
     *   ends at or before the stored end returns this same instance.
     * - **Unsettled days only.** A day that [isSettled] is a record (BR-10,
     *   BR-6's "never recomputed") and comes back untouched.
     * - **[startsAt] and [anchorTimezone] are never touched.**
     * - **A [window] for another label, or one that starts elsewhere, is
     *   refused**, loudly: it would mean the label had been reassigned to a
     *   different stretch of time, which the timeline must never do.
     *
     * Instants are compared and kept at microsecond precision, `timestamptz`'s
     * own, so a row read back equals the one written.
     *
     * **Called under the day's lock, in two places.** `SubmitEntry` calls it
     * for the day an entry is about to be filed on, before the insert.
     * `ReconcileJoiningDay` calls it for a couple's joining day while that
     * day is still `SUSPENDED` — and that one runs ahead of reads as well as
     * writes, so it is the single case in which `GET /today` extends a day.
     * Any other row opened before a change and never written to again keeps
     * its shorter span until C3's close job reconciles it from the timeline.
     * That is the accepted limit: until then the timeline, not this column,
     * says when such a day ends.
     *
     * **An obligation on every writer that opens a row without going through
     * `SubmitEntry`** — C3's close job above all (ADR-0031, Owed): take the
     * row's window from the bond's timeline, never from a zone's natural
     * midnight. A row whose `starts_at` disagrees with the timeline fails the
     * second `require` below on every later `POST /entries` for that day,
     * which is a `500` for both members until the row is repaired.
     */
    fun extendedTo(window: DayWindow): BondDay {
        require(window.date == date) { "a bond-day is only extended by its own label's window: $date, not ${window.date}" }
        require(window.startsAt.truncatedTo(ChronoUnit.MICROS) == startsAt.truncatedTo(ChronoUnit.MICROS)) {
            "a bond-day's start never moves: $startsAt, not ${window.startsAt}"
        }
        val end = window.endsAt.truncatedTo(ChronoUnit.MICROS)
        return if (isSettled || !end.isAfter(endsAt)) this else copy(endsAt = end)
    }

    companion object {
        /** V12's `bond_days_entry_count_check`, restated here so the schema and the domain agree by reading. */
        const val MAX_ENTRIES = 2

        /**
         * The ordinary open (doc 04 §3): the first thing either member's
         * write touches for a given bond and date, before any [Entry]
         * exists for it.
         *
         * [id] is supplied rather than minted here — as
         * [com.moyi.bond.domain.Bond.create] and [Entry.submit] both take
         * theirs — so the calling service mints it from the injected
         * [com.moyi.common.core.IdGenerator] port (`timeOrdered()`), the one
         * place this codebase gets a v7's bit layout, counter included,
         * right, and the one seam [com.moyi.common.testing.DeterministicIdGenerator]
         * can stand in for. See [BondDayId]'s class doc for the fuller reasoning.
         */
        fun open(
            id: BondDayId,
            bondId: UUID,
            window: DayWindow,
            zone: ZoneId,
            now: Instant,
        ): BondDay =
            BondDay(
                id = id,
                bondId = bondId,
                date = window.date,
                status = BondDayStatus.OPEN,
                anchorTimezone = zone,
                startsAt = window.startsAt,
                endsAt = window.endsAt,
                entryCount = 0,
                revealedAt = null,
                closedAt = null,
                createdAt = now,
                version = 0,
            )

        /**
         * The [BondDayStatus.SUSPENDED] open (doc 04 §8.3a, as the Phase 3
         * design §12.4 resolves it): a bond still waiting for its second
         * member has no calendar running yet, but `02` J1 lets its creator
         * write before that member arrives — so the row has to exist for
         * their [Entry] to hang off, and it opens excluded from evaluation
         * from the first write rather than becoming so later.
         *
         * [id], as [open]'s own doc explains, is the caller's to mint. The
         * body below duplicates [open]'s rather than sharing a
         * private helper: a shared six-parameter factory tripped detekt's
         * `LongParameterList` on a function that would have existed for no
         * reason but to avoid this duplication, which is the worse trade.
         */
        fun openSuspended(
            id: BondDayId,
            bondId: UUID,
            window: DayWindow,
            zone: ZoneId,
            now: Instant,
        ): BondDay =
            BondDay(
                id = id,
                bondId = bondId,
                date = window.date,
                status = BondDayStatus.SUSPENDED,
                anchorTimezone = zone,
                startsAt = window.startsAt,
                endsAt = window.endsAt,
                entryCount = 0,
                revealedAt = null,
                closedAt = null,
                createdAt = now,
                version = 0,
            )
    }
}
