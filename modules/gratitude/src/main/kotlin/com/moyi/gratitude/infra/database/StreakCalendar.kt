package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayOutcome
import com.moyi.gratitude.domain.StreakCell
import com.moyi.gratitude.domain.StreakDay
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * The bond's days evaluated `COMPLETE`: how many there are, and the dates of those asked for by
 * their place among them, oldest first (the 7th, the 30th…). What `GetMilestones` dates each
 * milestone by, and what `EvaluateStreaks` counts a crossing from (ADR-0041 decision 2), so
 * that the day an announcement names is the day the read shows.
 */
internal data class CompleteDays(
    val total: Int,
    val dates: Map<Int, LocalDate>,
)

/**
 * A bond's days as the heatmap wants them: a date and a square, and nothing
 * else of the row. Apart from [StreakStore], which is the evaluation's and
 * assumes its lock; this is a read for a member, and takes none. (The
 * evaluation also asks [completeDays], under its own lock, so that the
 * milestones it announces are counted as the read counts them.)
 *
 * What a square may say is [StreakCell]'s to decide; this only reads the two
 * columns it decides from.
 */
@Component
internal class StreakCalendar(
    private val jdbc: JdbcTemplate,
) {
    /** The bond's squares from [from] to [to], oldest first. A date the calendar does not draw is not in the list. */
    fun between(
        bondId: UUID,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
    ): List<StreakDay> =
        jdbc
            .query(
                "SELECT date, status, evaluated_as FROM bond_days WHERE bond_id = ? AND date BETWEEN ? AND ? ORDER BY date",
                { row, _ ->
                    val date = row.getObject("date", LocalDate::class.java)
                    val status = BondDayStatus.valueOf(row.getString("status"))
                    val outcome = row.getString("evaluated_as")?.let(DayOutcome::valueOf)
                    StreakCell.of(status, outcome, isToday = date == today)?.let { StreakDay(date, it) }
                },
                bondId,
                from,
                to,
            ).filterNotNull()

    /**
     * [CompleteDays] for the bond, with the dates of the [ordinals]th complete days. **One
     * statement**, so the count and the dates are of one moment. The rows kept are the ones asked
     * for and the last, which carries the count when none of those has been reached. The
     * ordinals are one bound parameter, never part of the SQL.
     *
     * **The timeline, not `streak_states`.** The projection holds a count and one date; the
     * timeline holds every date, is what `recalculate` trusts (FR-074), and a day's
     * `evaluated_as` is never recomputed from its entries (BR-10).
     */
    fun completeDays(
        bondId: UUID,
        ordinals: List<Int>,
    ): CompleteDays {
        val rows =
            jdbc.query(
                """
                SELECT n, date, total FROM (
                    SELECT date, row_number() OVER (ORDER BY date) AS n, count(*) OVER () AS total
                    FROM bond_days WHERE bond_id = ? AND evaluated_as = 'COMPLETE'
                ) complete
                WHERE n = ANY (?::int[]) OR n = total
                """.trimIndent(),
                { row, _ -> Triple(row.getInt("n"), row.getObject("date", LocalDate::class.java), row.getInt("total")) },
                bondId,
                ordinals.joinToString(",", "{", "}"),
            )
        return CompleteDays(
            total = rows.firstOrNull()?.third ?: 0,
            dates = rows.filter { it.first in ordinals }.associate { it.first to it.second },
        )
    }
}
