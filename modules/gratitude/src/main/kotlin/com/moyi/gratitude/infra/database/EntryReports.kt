package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryReport
import com.moyi.gratitude.domain.MemberReport
import com.moyi.gratitude.domain.ReportDetail
import com.moyi.gratitude.domain.ReportReason
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `entry_reports` (V39, V46): what a member reported, an entry or the other
 * member, and why (FR-100, ADR-0053 and its amendment for #127). One table
 * for both targets, so that both are one queue for the operator.
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
            "INSERT INTO entry_reports (id, target, entry_id, bond_id, reporter_member_id, reason, detail, status, created_at) " +
                "VALUES (?, 'ENTRY', ?, ?, ?, ?, ?, 'OPEN', ?)",
            report.id,
            report.entryId.value,
            report.bondId,
            report.reporterMemberId,
            report.reason.name,
            detail?.value,
            Timestamp.from(at),
        )
    }

    /** The entry report [id] names, whoever made it, or null: the caller checks it is theirs. */
    fun find(id: UUID): EntryReport? =
        jdbc
            .query(
                "SELECT id, entry_id, bond_id, reporter_member_id, reason FROM entry_reports WHERE id = ? AND target = 'ENTRY'",
                ROW,
                id,
            ).singleOrNull()

    /** A new, open member report, in the caller's transaction: no entry, and nothing for `media` to hold. */
    fun insert(
        report: MemberReport,
        detail: ReportDetail?,
        at: Instant,
    ) {
        jdbc.update(
            "INSERT INTO entry_reports " +
                "(id, target, bond_id, reporter_member_id, reported_member_id, reason, detail, status, created_at) " +
                "VALUES (?, 'MEMBER', ?, ?, ?, ?, ?, 'OPEN', ?)",
            report.id,
            report.bondId,
            report.reporterMemberId,
            report.reportedMemberId,
            report.reason.name,
            detail?.value,
            Timestamp.from(at),
        )
    }

    /** The member report [id] names, whoever made it, or null: the caller checks it is theirs. */
    fun findMember(id: UUID): MemberReport? =
        jdbc
            .query(
                "SELECT id, bond_id, reporter_member_id, reported_member_id, reason FROM entry_reports " +
                    "WHERE id = ? AND target = 'MEMBER'",
                MEMBER_ROW,
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

        val MEMBER_ROW =
            RowMapper { rs, _ ->
                MemberReport(
                    id = rs.getObject("id", UUID::class.java),
                    bondId = rs.getObject("bond_id", UUID::class.java),
                    reporterMemberId = rs.getObject("reporter_member_id", UUID::class.java),
                    reportedMemberId = rs.getObject("reported_member_id", UUID::class.java),
                    reason = ReportReason.valueOf(rs.getString("reason")),
                )
            }
    }
}
