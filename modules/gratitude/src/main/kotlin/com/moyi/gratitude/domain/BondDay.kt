package com.moyi.gratitude.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Doc 04 §3's state machine for a Bond-day, all **eight** values — even
 * though this slice (C1) only ever writes three of them ([OPEN], [PARTIAL]
 * and [SUSPENDED]).
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
 * **This slice deliberately produces no reveal.** A day with two entries
 * stays [BondDayStatus.PARTIAL] — see [withEntry] — and the transition to
 * [BondDayStatus.REVEALED] belongs to the next slice, because it needs a row
 * lock and a concurrency test, neither of which belongs in a pure aggregate
 * with no store to lock through. Read the absence of a `reveal()` method
 * here as that decision, not as an oversight: `BondDayTest` asserts the
 * second entry does *not* reveal the day, on purpose.
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
    /** The row version behind the `ETag` (doc 06 §1), as [com.moyi.bond.domain.Bond.version] is. `0` until a later slice updates it. */
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
     * One more entry has arrived (doc 04 §3) — the day row's own reaction to
     * what BR-2's unique index on `entries` just allowed through.
     *
     * **A [BondDayStatus.SUSPENDED] day stays [BondDayStatus.SUSPENDED].**
     * The creator may write before their partner joins (`02` J1), and that
     * write has to land somewhere (spec §12.4), but doc 04 §8.3a excludes a
     * suspended day from evaluation altogether — so an entry landing on one
     * changes [entryCount] and changes nothing else about it. [SUSPENDED] is
     * its own mechanism, not [BondDayStatus.OPEN] under a different name:
     * the close job (C3) and the streak walk both skip it by status, and
     * flipping the status here would put it back in their path.
     *
     * **Nothing in this codebase ever moves a day out of [BondDayStatus.SUSPENDED]
     * once opened — that is the next slice's explicit obligation, not
     * something this method defers by accident** (F4, whole-branch review;
     * see ADR-0031 §4's own amendment). Walk the scenario this leaves open:
     * Ada creates a bond and writes on day D — [BondDay.openSuspended] opens
     * it [SUSPENDED]. Bea accepts the invite later the same day and writes
     * too — [withEntry] runs *again*, on the same row, and this clause keeps
     * it [SUSPENDED] rather than promoting it to [BondDayStatus.PARTIAL] the
     * way an ordinary day's second entry does. The row now has `entry_count
     * = 2` and `status = SUSPENDED` — both entries present, the bond no
     * longer awaiting a second member — and nothing ever writes that status
     * again: the reveal C2 adds looks for [BondDayStatus.PARTIAL] or
     * [BondDayStatus.PENDING_REVEAL], and C3's close job excludes
     * [SUSPENDED] from its own partial index (`bond_days_open_idx`,
     * V12) by design, precisely because a genuinely-still-suspended day must
     * not be swept into a close it does not qualify for. **Whichever slice
     * next reads or writes this status has to add the transition that
     * un-suspends a day once its bond stops awaiting a second member** — most
     * naturally, the moment the second member's own membership is created,
     * not buried in this method or in a later entry's own [withEntry] call.
     * Miss it, and the couple's first shared day — the one this whole slice
     * exists to let them write on together — never reveals, and both of
     * their first entries stay locked to each other permanently. This method
     * is deliberately left doing nothing about that: inventing the
     * transition here, without the reveal's own state machine in view, risks
     * building the wrong one.
     *
     * **Every other day becomes [BondDayStatus.PARTIAL], including on the
     * second entry.** C1 has no reveal — see the class doc — so there is no
     * third value this could become yet; the second call is left honest
     * about [entryCount] and wrong about nothing else.
     */
    fun withEntry(): BondDay =
        copy(
            entryCount = entryCount + 1,
            status = if (status == BondDayStatus.SUSPENDED) BondDayStatus.SUSPENDED else BondDayStatus.PARTIAL,
        )

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
     * **Called under the day's lock, by a write** (`SubmitEntry`), before the
     * entry is inserted. `GET /today` is a read and extends nothing, so a row
     * opened before a change and never written to again keeps its shorter
     * span until C3's close job reconciles it from the timeline. That is the
     * accepted limit: until then the timeline, not this column, says when
     * such a day ends.
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
