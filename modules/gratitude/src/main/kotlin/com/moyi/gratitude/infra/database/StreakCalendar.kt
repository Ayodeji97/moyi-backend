package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.StreakDay
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * A bond's days as the heatmap wants them: a date and a status, and nothing
 * else of the row. Apart from [StreakStore], which is the evaluation's and
 * assumes its lock; this is a read for a member, and takes none.
 */
@Component
internal class StreakCalendar(
    private val jdbc: JdbcTemplate,
) {
    /** The bond's days from [from] to [to], oldest first. A date with no row is not in the list. */
    fun between(
        bondId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<StreakDay> =
        jdbc.query(
            "SELECT date, status FROM bond_days WHERE bond_id = ? AND date BETWEEN ? AND ? ORDER BY date",
            { row, _ -> StreakDay(row.getObject("date", LocalDate::class.java), BondDayStatus.valueOf(row.getString("status"))) },
            bondId,
            from,
            to,
        )
}
