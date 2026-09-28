package com.moyi.gratitude.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 * delegates to it.** A later slice has to ask this question of a status it
 * read straight off a row — `BondDayStore.statusOf(bondId, date):
 * BondDayStatus?` (Task 6) — without paying for the whole aggregate. Putting
 * the answer only on [BondDay] would have given that caller nothing to ask,
 * and putting it in two places would have given the codebase two chances to
 * disagree with itself about what "closed" means.
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
 * **[anchorTimezone] is copied onto the row, not read live off the bond.**
 * BR-6 and ADR-0030 require a zone change to never recompute an existing
 * day — [DayAssignment] already filed every day that came before a move
 * under whatever zone was current when it was filed, and reading the bond's
 * *current* zone from here would retroactively move all of them the moment
 * the anchor changes. [open] and [openSuspended] both stamp the zone they
 * were called with; nothing on this aggregate ever reads it back off the
 * bond.
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
    }

    /** [BondDayStatus.isClosed], on the aggregate for a caller who has already loaded the whole thing. */
    val isClosed: Boolean get() = status.isClosed

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

    companion object {
        /** V12's `bond_days_entry_count_check`, restated here so the schema and the domain agree by reading. */
        const val MAX_ENTRIES = 2

        /**
         * The ordinary open (doc 04 §3): the first thing either member's
         * write touches for a given bond and date, before any [Entry]
         * exists for it.
         */
        fun open(
            bondId: UUID,
            date: LocalDate,
            zone: ZoneId,
            now: Instant,
        ): BondDay = newDay(bondId, date, zone, now, status = BondDayStatus.OPEN)

        /**
         * The [BondDayStatus.SUSPENDED] open (doc 04 §8.3a, as the Phase 3
         * design §12.4 resolves it): a bond still waiting for its second
         * member has no calendar running yet, but `02` J1 lets its creator
         * write before that member arrives — so the row has to exist for
         * their [Entry] to hang off, and it opens excluded from evaluation
         * from the first write rather than becoming so later.
         */
        fun openSuspended(
            bondId: UUID,
            date: LocalDate,
            zone: ZoneId,
            now: Instant,
        ): BondDay = newDay(bondId, date, zone, now, status = BondDayStatus.SUSPENDED)

        private fun newDay(
            bondId: UUID,
            date: LocalDate,
            zone: ZoneId,
            now: Instant,
            status: BondDayStatus,
        ): BondDay =
            BondDay(
                id = BondDayId.fresh(now),
                bondId = bondId,
                date = date,
                status = status,
                anchorTimezone = zone,
                entryCount = 0,
                revealedAt = null,
                closedAt = null,
                createdAt = now,
                version = 0,
            )
    }
}
