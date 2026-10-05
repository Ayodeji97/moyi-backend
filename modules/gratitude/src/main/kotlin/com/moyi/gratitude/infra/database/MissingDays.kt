package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayWindow
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The close job's writes for days nobody opened (spec §6.4 step 1): which
 * dates a bond already has, and a row for one it does not — written
 * **closed**, in one statement.
 *
 * A day nobody wrote on has no reason to exist as `OPEN` first: it has
 * already ended, so opening it would only make a row that a submission could
 * find, lock and be refused on, for the length of one more statement. And
 * `ON CONFLICT DO NOTHING` on the unique `(bond_id, date)` is the whole of
 * the concurrency story: if a submission opened the date a moment ago, its
 * row stands, this insert does nothing, and the sweep settles that row like
 * any other. No lock is taken, because there is no row to lock.
 */
@Component
internal class MissingDays(
    private val jdbc: JdbcTemplate,
) {
    fun datesOf(bondId: UUID): Set<LocalDate> =
        jdbc
            .query("SELECT date FROM bond_days WHERE bond_id = ?", { row, _ -> row.getObject(1, LocalDate::class.java) }, bondId)
            .toSet()

    /** True when the row was written; false when the date already had one. */
    @Suppress("LongParameterList") // The row's own columns; there is no smaller honest shape.
    fun insertClosed(
        id: UUID,
        bondId: UUID,
        window: DayWindow,
        zone: String,
        status: BondDayStatus,
        now: Instant,
    ): Boolean {
        val at = Timestamp.from(now.truncatedTo(ChronoUnit.MICROS))
        return jdbc.update(
            """
            INSERT INTO bond_days
                (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, closed_at, created_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 0)
            ON CONFLICT (bond_id, date) DO NOTHING
            """.trimIndent(),
            id,
            bondId,
            window.date,
            status.name,
            zone,
            Timestamp.from(window.startsAt.truncatedTo(ChronoUnit.MICROS)),
            Timestamp.from(window.endsAt.truncatedTo(ChronoUnit.MICROS)),
            at,
            at,
        ) == 1
    }
}
