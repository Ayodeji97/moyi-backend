package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.SearchTerms
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
    /** For a search ([ArchiveDays.matching]), which of this day's entries matched it. Empty for every other listing. */
    val matched: Set<EntryId> = emptySet(),
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
 * `Entry.isErased` asks both), excluding authors already known to have
 * withdrawn. The exclusion is passed through BondAccess by the caller:
 * no query reaches into the bond module's tables. It applies BEFORE LIMIT,
 * so years of withdrawn bookmarks cannot create empty pages and cursors.
 * It is still a filter: the caller loads the entries, asks who has withdrawn
 * again, and gates each entry. A withdrawal racing this query is caught there.
 * Only ever [memberId]'s own marks are considered.
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
        withdrawnMemberIds: Set<UUID> = emptySet(),
    ): List<ArchiveDay> {
        val (sql, arguments) = query(bondId, memberId, before, until, favouritesOnly, limit, withdrawnMemberIds)
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

    /**
     * At most [limit] days of [bondId], newest first and strictly before [before] where given,
     * on which **one entry** matches [terms] and that same entry is one [memberId] may read in
     * full as far as rows can say: they wrote it or it was revealed ([SEEN_ROW]), and its
     * author is not in [withdrawnMemberIds]. Each day carries the ids of its entries that
     * matched (ADR-0039 decisions 2 and 3).
     *
     * **The match and the visibility are asked of one row.** Asked of the day, "a day with an
     * entry the caller can see, and an entry that matches", the partner's unrevealed words
     * would list a day the caller wrote on, and say what the partner wrote before the reveal
     * (c6-corpus H-S2).
     *
     * **An erased entry cannot match**: its projection is `NULL` and its vector empty
     * (`searchProjectionOf`, V25), so nothing here asks about erasure again.
     *
     * **The known withdrawn are excluded before `LIMIT`**, as the favourites feed does
     * (ADR-0036 decision 7): a history of words taken back but not yet erased cannot fill a
     * page with candidates the gate will drop. It is still a filter. The caller loads the
     * entries, asks who has withdrawn again, and shows a day only if a matched entry was read
     * in full.
     *
     * **`q` is a parameter**, folded by the function V25's generated column folds with, and
     * parsed by `plainto_tsquery` (T-11): every word, as a whole word, in one entry.
     *
     * Driven from `entries`, so the planner may start from `entries_search_idx`; the measured
     * cost is in ADR-0039.
     *
     * **The join names the bond on both sides** (`d.bond_id = e.bond_id`). Without it nothing
     * bounds `bond_days` to the bond, and for a word the bond uses often the planner was free to
     * hash-join a sequential scan of every bond's days: 49,980 rows at 100k entries
     * (`SearchPlanMeasurement`, task 2 review I2). With it Postgres derives `d.bond_id = ?` and
     * reads the bond's days alone. It also holds, in the query, that an entry and its day belong
     * to one bond, which no foreign key does.
     */
    @Suppress("LongParameterList") // The search's own bounds, each a separate fact of the request.
    fun matching(
        bondId: UUID,
        memberId: UUID,
        terms: SearchTerms,
        before: LocalDate?,
        limit: Int,
        withdrawnMemberIds: Set<UUID>,
    ): List<ArchiveDay> {
        val (sql, arguments) = matchingQuery(bondId, memberId, terms, before, limit, withdrawnMemberIds)
        return jdbc.query(
            sql,
            PreparedStatementSetter { statement -> arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) } },
            MATCHED,
        )
    }

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

        /**
         * BR-1's "ever readable" asked of one entry row `e`: the caller wrote it, or it has been
         * revealed. Its one argument is the caller's member id. The archive lists a day by it
         * ([SEEN]); a search asks it of the very row that matched ([matching]), never of the day.
         */
        private const val SEEN_ROW = "(e.author_member_id = ? OR e.revealed_at IS NOT NULL)"

        private const val SEEN = "AND EXISTS (SELECT 1 FROM entries e WHERE e.bond_day_id = d.id AND $SEEN_ROW)"

        /** Neither mark of an erasure, as `Entry.isErased` asks both. No argument. */
        private const val LIVE_ROW = "e.deleted_at IS NULL AND e.status <> 'DELETED'"

        private const val MARKED =
            "AND EXISTS (SELECT 1 FROM entries e JOIN entry_favourites f ON f.entry_id = e.id " +
                "WHERE e.bond_day_id = d.id AND f.member_id = ? " +
                "AND e.revealed_at IS NOT NULL AND $LIVE_ROW"

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
            withdrawnMemberIds: Set<UUID> = emptySet(),
        ): Pair<String, List<Any>> {
            val excluded = withdrawnMemberIds.takeIf { favouritesOnly }.orEmpty()
            val excluding =
                if (excluded.isEmpty()) "" else " AND e.author_member_id NOT IN (${excluded.joinToString { "?" }})"
            val sql =
                listOfNotNull(
                    SELECT,
                    "WHERE d.bond_id = ?",
                    before?.let { "AND d.date < ?" },
                    until?.let { "AND d.date <= ?" },
                    SEEN,
                    "$MARKED$excluding)".takeIf { favouritesOnly },
                    "ORDER BY d.date DESC",
                    "LIMIT ?",
                ).joinToString("\n")
            val arguments = listOfNotNull(bondId, before, until, memberId, memberId.takeIf { favouritesOnly }) + excluded + limit
            return sql to arguments
        }

        /** [matching]'s statement and its arguments, in order: apart so that a measurement can ask Postgres how it runs. */
        @Suppress("LongParameterList")
        fun matchingQuery(
            bondId: UUID,
            memberId: UUID,
            terms: SearchTerms,
            before: LocalDate?,
            limit: Int,
            withdrawnMemberIds: Set<UUID>,
        ): Pair<String, List<Any>> {
            val sql =
                listOfNotNull(
                    "SELECT d.id, d.date, d.status, array_agg(e.id) AS matched",
                    "FROM entries e JOIN bond_days d ON d.id = e.bond_day_id AND d.bond_id = e.bond_id",
                    "WHERE e.bond_id = ?",
                    before?.let { "AND d.date < ?" },
                    "AND e.search_vector @@ plainto_tsquery('simple', public.immutable_unaccent(?))",
                    "AND $SEEN_ROW",
                    withdrawnMemberIds
                        .takeIf { it.isNotEmpty() }
                        ?.let { ids -> "AND e.author_member_id NOT IN (${ids.joinToString { "?" }})" },
                    "GROUP BY d.id, d.date, d.status",
                    "ORDER BY d.date DESC",
                    "LIMIT ?",
                ).joinToString("\n")
            val arguments = listOfNotNull<Any>(bondId, before, terms.value, memberId) + withdrawnMemberIds + limit
            return sql to arguments
        }

        private val MATCHED =
            RowMapper { row, _ ->
                ArchiveDay(
                    BondDayId(row.getObject("id", UUID::class.java)),
                    row.getObject("date", LocalDate::class.java),
                    BondDayStatus.valueOf(row.getString("status")),
                    (row.getArray("matched").array as Array<*>).map { EntryId(it as UUID) }.toSet(),
                )
            }
    }
}
