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
 * A bond's days as the heatmap wants them: a date and a square, and nothing
 * else of the row. Apart from [StreakStore], which is the evaluation's and
 * assumes its lock; this is a read for a member, and takes none.
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
}
