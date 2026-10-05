package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One day the close job should look at: enough to settle it and to ask for the page after it. */
internal data class CloseCandidate(
    val id: BondDayId,
    val bondId: UUID,
    val date: LocalDate,
)

/**
 * The days the close job should look at (spec §6.4 step 2): every unclosed
 * day that **may** have ended, and every day waiting on a reveal time.
 *
 * **`ends_at <= now` finds every day that has ended, and some that have
 * not.** A day's end only ever moves later (ADR-0031 decision 13), so its
 * true end is never before the stored one: no ended day is missed. A row
 * opened before a westward zone change is found early; `CloseDay` extends it
 * under its lock and answers "not yet". That is why this is a filter and
 * never the decision, and why no bond has to be visited to find days that
 * already have a row.
 *
 * `PENDING_REVEAL` days are included whatever their end, for the second sweep
 * (spec §6.3): their reveal time falls inside the day.
 *
 * Paged by `(bond_id, date)` and not by offset: a day that is looked at and
 * left ("not yet") is still a candidate, and an offset over a set that
 * shrinks as it is worked through skips rows. The order also gives each
 * bond's days oldest first.
 *
 * Plain SQL, not an entity query: this reads three columns of rows that
 * `CloseDay` will lock and read properly, and an entity loaded here would
 * only be something stale to be handed back later.
 */
@Component
internal class CloseCandidates(
    private val jdbc: JdbcTemplate,
) {
    fun after(
        previous: CloseCandidate?,
        now: Instant,
        limit: Int,
    ): List<CloseCandidate> =
        jdbc.query(
            """
            SELECT id, bond_id, date FROM bond_days
            WHERE closed_at IS NULL
              AND (ends_at <= ? OR status = 'PENDING_REVEAL')
              AND (bond_id, date) > (?, ?)
            ORDER BY bond_id, date
            LIMIT ?
            """.trimIndent(),
            { row, _ ->
                CloseCandidate(
                    BondDayId(row.getObject("id", UUID::class.java)),
                    row.getObject("bond_id", UUID::class.java),
                    row.getObject("date", LocalDate::class.java),
                )
            },
            Timestamp.from(now),
            previous?.bondId ?: FIRST_BOND,
            previous?.date ?: FIRST_DATE,
            limit,
        )

    private companion object {
        val FIRST_BOND = UUID(0, 0)
        val FIRST_DATE: LocalDate = LocalDate.of(1, 1, 1)
    }
}
