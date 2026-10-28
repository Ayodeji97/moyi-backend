package com.moyi.gratitude.infra.database

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** Whether a member has written on a bond-day: the one fact the daily reminder needs of the entries (ADR-0045). */
@Component
internal class WrittenToday(
    private val jdbc: JdbcTemplate,
) {
    /**
     * An entry by [memberId] on [bondId]'s day [date] that has not been
     * deleted. A deleted entry is not one written: the member may write again
     * that day, and the reminder is for that.
     */
    fun hasWritten(
        bondId: UUID,
        date: LocalDate,
        memberId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM entries e JOIN bond_days d ON d.id = e.bond_day_id
                 WHERE d.bond_id = ? AND d.date = ? AND e.author_member_id = ? AND e.deleted_at IS NULL)
            """.trimIndent(),
            Boolean::class.java,
            bondId,
            date,
            memberId,
        ) == true
}
