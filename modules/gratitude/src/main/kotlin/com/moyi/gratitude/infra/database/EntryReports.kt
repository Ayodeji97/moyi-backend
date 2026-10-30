package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryReport
import com.moyi.gratitude.domain.ReportDetail
import com.moyi.gratitude.domain.ReportReason
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `entry_reports` (V39): what a member reported, and why (FR-100, ADR-0053).
 *
 * Plain SQL on [JdbcTemplate], as `Reactions` and `Favourites` are: a report
 * is ids, a reason and the reporter's words, and stays out of Hibernate's
 * persistence context. **The detail is written and never read here**: the
 * application has no path that reads it back, so no response, log line or
 * exception the application makes can carry it. The operator reads it through
 * `entry_report_read`, which records that they did.
 *
 * It decides nothing about who may report what; `ReportEntry` has asked the
 * read gate before [insert] is reached.
 */
@Component
internal class EntryReports(
    private val jdbc: JdbcTemplate,
) {
    /** A new, open report, in the caller's transaction. */
    fun insert(
        report: EntryReport,
        detail: ReportDetail?,
        at: Instant,
    ) {
        jdbc.update(
            "INSERT INTO entry_reports (id, entry_id, bond_id, reporter_member_id, reason, detail, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?)",
            report.id,
            report.entryId.value,
            report.bondId,
            report.reporterMemberId,
            report.reason.name,
            detail?.value,
            Timestamp.from(at),
        )
    }

    /** The report [id] names, whoever made it, or null: the caller checks it is theirs. */
    fun find(id: UUID): EntryReport? =
        jdbc
            .query(
                "SELECT id, entry_id, bond_id, reporter_member_id, reason FROM entry_reports WHERE id = ?",
                ROW,
                id,
            ).singleOrNull()

    private companion object {
        val ROW =
            RowMapper { rs, _ ->
                EntryReport(
                    id = rs.getObject("id", UUID::class.java),
                    entryId = EntryId(rs.getObject("entry_id", UUID::class.java)),
                    bondId = rs.getObject("bond_id", UUID::class.java),
                    reporterMemberId = rs.getObject("reporter_member_id", UUID::class.java),
                    reason = ReportReason.valueOf(rs.getString("reason")),
                )
            }
    }
}
