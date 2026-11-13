package com.moyi.gratitude.infra.database

import com.moyi.bond.api.BondAccess
import com.moyi.common.core.IdGenerator
import com.moyi.gratitude.api.ReportedEntryRead
import com.moyi.gratitude.api.ReportedEntryText
import com.moyi.gratitude.api.ReportedWords
import com.moyi.gratitude.api.WordsRefusal
import com.moyi.gratitude.domain.EntryStatus
import com.moyi.gratitude.domain.ReportReason
import com.moyi.gratitude.domain.WordsReadable
import com.moyi.gratitude.domain.wordsReadable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [ReportedEntryText] (A5 spec §4, §5 step 4, §8): the report `FOR SHARE`, the domain's rule,
 * the entry `FOR SHARE` with its bond-day's date, the withdrawn authors from the bond's
 * unlocked view, and the `WORDS_READ` row, all in the caller's transaction.
 *
 * **Lock order: report, then entry. No bond lock**, deliberately. The reporter's own account
 * erasure (`EraseDeletedAccount`) takes the bond's lock and then `UPDATE entry_reports SET
 * detail = NULL` on this report; a read holding the report's share lock and waiting on that
 * bond would close a cycle. So the withdrawn authors are read through
 * [BondAccess.closingViewOf], which takes no lock, after the entry's lock: a withdrawal that
 * committed before that statement is seen, as `Reader` reads it after the entry was loaded.
 *
 * Nothing here prints the words: no log, no exception message carries them, and the only
 * value that holds them is [ReportedWords]. A `@Component`, as [ReportModerationStore] is.
 */
@Component
internal class ReportedEntryTextStore(
    jdbcTemplate: JdbcTemplate,
    private val bonds: BondAccess,
    private val ids: IdGenerator,
    private val clock: Clock,
) : ReportedEntryText {
    private val jdbc = JdbcClient.create(jdbcTemplate)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun read(
        reportId: UUID,
        expectedReason: String,
        actor: String,
    ): ReportedEntryRead {
        // No status filter: a report decided meanwhile still names its entry, which NotOpen carries.
        val report = report(reportId, lock = true)
        return when {
            report == null -> {
                ReportedEntryRead.NotOpen(null)
            }

            report.status != OPEN -> {
                ReportedEntryRead.NotOpen(report.entryId)
            }

            report.reason.name != expectedReason -> {
                ReportedEntryRead.Stale(report.entryId)
            }

            // NOT_OPEN cannot be the rule's answer here: the status was asked first.
            report.readable != WordsReadable.YES -> {
                ReportedEntryRead.NotAboutWords(
                    report.entryId,
                    report.reason.name,
                    imageReason = report.readable == WordsReadable.IMAGE_REASON,
                )
            }

            else -> {
                entry(checkNotNull(report.entryId) { "an entry report names no entry" }, report.bondId)
                    .also { logRead(reportId, actor) }
            }
        }
    }

    /** The report's `WORDS_READ` row: who read, when; no note, and never a word of the entry. */
    private fun logRead(
        reportId: UUID,
        actor: String,
    ) {
        jdbc
            .sql(
                "INSERT INTO entry_report_log (id, report_id, action, actor, note, at) " +
                    "VALUES (:id, :report, 'WORDS_READ', :actor, NULL, :at)",
            ).param("id", ids.timeOrdered())
            .param("report", reportId)
            .param("actor", actor)
            .param("at", Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)))
            .update()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun whyNot(reportId: UUID): WordsRefusal? =
        when (report(reportId, lock = false)?.readable ?: WordsReadable.NOT_OPEN) {
            WordsReadable.YES -> null
            WordsReadable.NOT_OPEN -> WordsRefusal.NOT_OPEN
            WordsReadable.MEMBER_REPORT -> WordsRefusal.MEMBER_REPORT
            WordsReadable.IMAGE_REASON -> WordsRefusal.IMAGE_REASON
        }

    private fun report(
        reportId: UUID,
        lock: Boolean,
    ): ReportRow? =
        jdbc
            .sql(REPORT + if (lock) " FOR SHARE" else "")
            .param("id", reportId)
            .query(REPORT_ROW)
            .optional()
            .orElse(null)

    /**
     * The entry under its share lock, and what an `ADMIN` may have of it. Erased when the row
     * is (either mark, as `Entry.isErased` counts it), when its author has withdrawn from the
     * bond, when the bond is gone, or when the row itself is gone (a report outlives its
     * entry's row, V39); otherwise its words. A live row with no text is a broken invariant
     * (`Entry.init`: only an erased entry has no text), thrown before the caller logs the read.
     */
    private fun entry(
        entryId: UUID,
        bondId: UUID,
    ): ReportedEntryRead {
        val row =
            jdbc
                .sql(
                    "SELECT e.text, e.status, e.deleted_at IS NOT NULL AS deleted, e.author_member_id, d.date " +
                        "FROM entries e JOIN bond_days d ON d.id = e.bond_day_id WHERE e.id = :entry FOR SHARE OF e",
                ).param("entry", entryId)
                .query(ENTRY_ROW)
                .optional()
                .orElse(null)
        // After the entry's lock, without the bond's (see the class KDoc).
        val withdrawn = row?.let { bonds.closingViewOf(bondId)?.withdrawnMemberIds }
        return when {
            row == null || withdrawn == null || row.erased || row.authorMemberId in withdrawn -> {
                ReportedEntryRead.Erased(entryId)
            }

            else -> {
                ReportedEntryRead.Shown(
                    entryId,
                    row.date,
                    ReportedWords(checkNotNull(row.text) { "entry $entryId is live and has no text" }),
                )
            }
        }
    }

    private class ReportRow(
        val reason: ReportReason,
        val status: String,
        val entryId: UUID?,
        val bondId: UUID,
        val readable: WordsReadable,
    )

    /** Never printed: [text] is the entry's words. */
    private class EntryRow(
        val text: String?,
        val erased: Boolean,
        val authorMemberId: UUID,
        val date: LocalDate,
    ) {
        override fun toString(): String = "EntryRow(redacted)"
    }

    private companion object {
        const val OPEN = "OPEN"
        const val REPORT = "SELECT reason, status, target, entry_id, bond_id FROM entry_reports WHERE id = :id"

        val REPORT_ROW =
            RowMapper { rs, _ ->
                val reason = ReportReason.named(rs.getString("reason")) ?: error("entry_reports holds a reason ReportReason does not know")
                val status = rs.getString("status")
                ReportRow(
                    reason = reason,
                    status = status,
                    entryId = rs.getObject("entry_id", UUID::class.java),
                    bondId = rs.getObject("bond_id", UUID::class.java),
                    readable = reason.wordsReadable(rs.getString("target"), status),
                )
            }

        val ENTRY_ROW =
            RowMapper { rs, _ ->
                EntryRow(
                    text = rs.getString("text"),
                    erased = rs.getBoolean("deleted") || rs.getString("status") == EntryStatus.DELETED.name,
                    authorMemberId = rs.getObject("author_member_id", UUID::class.java),
                    date = rs.getObject("date", LocalDate::class.java),
                )
            }
    }
}
