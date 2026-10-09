package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.BondDayStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** One day the archive may list: enough to load its entries, to render it and to ask for the page after it. */
internal data class ArchiveDay(
    val id: BondDayId,
    val date: LocalDate,
    val status: BondDayStatus,
)

/**
 * The days of a bond a member has something to see on, newest first (FR-090):
 * the archive's one query.
 *
 * **Which days.** A day is a candidate for a member when it holds an entry
 * they wrote, or one that has been revealed. An erased row counts either
 * way: a tombstone the member could once read is still shown to them. So a
 * day both wrote on and read is here for both; a day whose only entry was
 * never revealed is here for its author alone; and a day holding only the
 * other person's unrevealed entry is not here at all, because listing it
 * would say that they wrote on a day the product has not said so. A day with
 * no entry row is never here, whatever its status.
 *
 * **This is a filter, and it decides nothing about what is shown.** It picks
 * days; every entry of a picked day is then read through the gate
 * (`Entry.canBeReadBy`), which is the only thing that says what a member may
 * see of it. The predicate is the gate's "ever readable" clause asked of a
 * row, so the two agree row by row, and `ArchiveGateTest` holds them to it
 * over every state a day and its entries can be in. It deliberately reads no
 * column of the day: a `SOLO` or `REVEALED` status is not evidence that an
 * entry was revealed (ADR-0033 decision 9: a lone entry on a bond that ended
 * before its day did never is).
 *
 * **Keyset on the date, never an offset.** "Strictly before this date" is
 * unaffected by a day that appears or is erased between two pages: no day is
 * repeated and none is skipped. [until] is the same bound made inclusive, for
 * jumping to a month. `(bond_id, date)` is unique, so the date alone is a
 * total order within a bond and the cursor needs nothing else.
 *
 * **Read in index order.** `bond_days_feed_idx (bond_id, date DESC)` gives
 * the rows already sorted, each day's entries are probed by
 * `entries_bond_day_idx`, and the scan stops at the limit, so a page costs
 * about a page **where the listed days are dense**. A day the member has
 * nothing to see on is walked past with one probe: a day with no entry row
 * (`EMPTY`, `FROZEN`, a `SUSPENDED` gap), or one holding only the other
 * person's unrevealed entry. So a page that crosses a year nobody wrote in
 * probes about 365 days, and the last page of any walk reads back to the
 * bond's first day. Measured by review: 3,000 days of which the member can
 * see the 5 oldest read 12,084 buffers in 2.4 ms for the first page;
 * 30,000 days with none visible, 27.7 ms. The bound is the bond's own days,
 * one row per calendar date, and never the table; NFR-033 holds by the
 * letter only for the dense case. `ArchiveDaysTest` reads the plan, on a
 * bond with a revealed day for every date, so it asserts the dense case
 * and no other. The bounds are written into the statement only when they
 * are given, so that each form is a plain range on the index and not an
 * `OR` the planner must guess about. **The favourites form can read
 * further**: it walks back until it has found enough marked days, which
 * for a member with few marks in a long history is most of the history,
 * one index probe a day.
 *
 * **With `favouritesOnly`**, the day must also hold an entry this member has
 * marked that is revealed and not erased (both marks of an erasure, as
 * `Entry.isErased` asks both). That is still only a filter: a marked entry
 * whose author has withdrawn, and which nothing has erased yet, is a row
 * like any other here. The caller reads it through the gate and drops the
 * day if nothing marked on it can still be read in full. Only ever
 * [memberId]'s own marks: there is no form of this query that asks about
 * anybody else's.
 *
 * Plain SQL on [JdbcTemplate], as `StreakCalendar` and `CloseCandidates`
 * are: three columns of rows nothing here will write. It takes no lock and
 * is not transactional itself; the caller's read-only transaction is the
 * boundary.
 */
@Component
internal class ArchiveDays(
    private val jdbc: JdbcTemplate,
) {
    /**
     * At most [limit] days of [bondId] that [memberId] has something to see
     * on, newest first: strictly before [before] and on or before [until],
     * each where given. [memberId] is the caller's member id in that bond,
     * from the membership the guard resolved.
     */
    @Suppress("LongParameterList") // The page's own bounds, each a separate fact of the request.
    fun candidates(
        bondId: UUID,
        memberId: UUID,
        before: LocalDate?,
        until: LocalDate?,
        favouritesOnly: Boolean,
        limit: Int,
    ): List<ArchiveDay> {
        val (sql, arguments) = query(bondId, memberId, before, until, favouritesOnly, limit)
        return jdbc.query(
            sql,
            PreparedStatementSetter { statement -> arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) } },
            ROW,
        )
    }

    /**
     * The day of [bondId] dated [date], **if it is one [memberId] has
     * something to see on**, and `null` otherwise: the archive's rule asked
     * of one date.
     *
     * The same statement [candidates] runs, with the date pinned where that
     * one bounds it: [SEEN] is written once and both read it, so a day can be
     * fetched here exactly when the feed would list it. `null` does not say
     * why. No row for the date, and a row holding only what the other person
     * wrote and this member has not been shown, are one answer from one
     * query, which is what lets the route above give them one response.
     */
    fun on(
        bondId: UUID,
        memberId: UUID,
        date: LocalDate,
    ): ArchiveDay? =
        jdbc
            .query(
                listOf(SELECT, "WHERE d.bond_id = ?", "AND d.date = ?", SEEN).joinToString("\n"),
                PreparedStatementSetter { statement ->
                    listOf(bondId, date, memberId).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                },
                ROW,
            ).firstOrNull()

    internal companion object {
        private const val SELECT = "SELECT d.id, d.date, d.status FROM bond_days d"

        private val ROW =
            RowMapper { row, _ ->
                ArchiveDay(
                    BondDayId(row.getObject("id", UUID::class.java)),
                    row.getObject("date", LocalDate::class.java),
                    BondDayStatus.valueOf(row.getString("status")),
                )
            }

        private const val SEEN =
            "AND EXISTS (SELECT 1 FROM entries e WHERE e.bond_day_id = d.id AND (e.author_member_id = ? OR e.revealed_at IS NOT NULL))"

        private const val MARKED =
            "AND EXISTS (SELECT 1 FROM entries e JOIN entry_favourites f ON f.entry_id = e.id " +
                "WHERE e.bond_day_id = d.id AND f.member_id = ? " +
                "AND e.revealed_at IS NOT NULL AND e.deleted_at IS NULL AND e.status <> 'DELETED')"

        /**
         * The statement and its arguments, in order. Apart from [candidates]
         * so that a test can ask Postgres how it would run exactly this.
         * Nothing of the request is written into the text: the bounds and
         * the filter choose between fixed fragments, and every value is a
         * parameter.
         */
        @Suppress("LongParameterList")
        fun query(
            bondId: UUID,
            memberId: UUID,
            before: LocalDate?,
            until: LocalDate?,
            favouritesOnly: Boolean,
            limit: Int,
        ): Pair<String, List<Any>> {
            val sql =
                listOfNotNull(
                    SELECT,
                    "WHERE d.bond_id = ?",
                    before?.let { "AND d.date < ?" },
                    until?.let { "AND d.date <= ?" },
                    SEEN,
                    MARKED.takeIf { favouritesOnly },
                    "ORDER BY d.date DESC",
                    "LIMIT ?",
                ).joinToString("\n")
            val arguments = listOfNotNull(bondId, before, until, memberId, memberId.takeIf { favouritesOnly }, limit)
            return sql to arguments
        }
    }
}
