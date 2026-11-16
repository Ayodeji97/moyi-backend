package com.moyi.gratitude.infra.database

import com.moyi.gratitude.api.DateSettlement
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/**
 * The date check's one statement (ADR-0067 decision 3): the days labelled a date, counted by where
 * the close job has got with them. "Past due" is the close job's own candidate condition
 * (`CloseCandidates`): unclosed, and ended by its threshold or waiting on a reveal.
 */
@Component
internal class DateSettlementQuery(
    private val jdbc: JdbcTemplate,
) {
    fun on(
        date: LocalDate,
        endedAsOf: Instant,
    ): DateSettlement =
        jdbc.queryForObject(
            """
            SELECT count(*) FILTER (WHERE closed_at IS NULL) AS open,
                   count(*) FILTER (WHERE closed_at IS NULL AND (ends_at <= ? OR status = 'PENDING_REVEAL')) AS past_due,
                   count(*) FILTER (WHERE closed_at IS NOT NULL AND evaluated_at IS NULL) AS uncounted
            FROM bond_days WHERE date = ?
            """.trimIndent(),
            { rs, _ -> DateSettlement(rs.getInt("open"), rs.getInt("past_due"), rs.getInt("uncounted")) },
            Timestamp.from(endedAsOf),
            date,
        )!!
}
