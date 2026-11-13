package com.moyi.admin.service

import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.common.security.ClientAddress
import com.moyi.gratitude.api.ReportedEntryRead
import com.moyi.gratitude.api.ReportedEntryText
import com.moyi.gratitude.api.WordsRefusal
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * What became of one read of a reported entry's words (A5 spec §5): each is a `metadata.result`
 * on its `ENTRY_TEXT_READ` row and a tag of `moyi.admin.content.read`. [SHOWN] and [ERASED]
 * completed (`SUCCEEDED`); the rest were refused (`REFUSED`).
 */
internal enum class EntryTextOutcome(
    val succeeded: Boolean,
) {
    SHOWN(succeeded = true),
    ERASED(succeeded = true),
    NOT_OPEN(succeeded = false),
    STALE(succeeded = false),
    NOT_ABOUT_WORDS(succeeded = false),
    ;

    companion object {
        fun of(read: ReportedEntryRead): EntryTextOutcome =
            when (read) {
                is ReportedEntryRead.Shown -> SHOWN
                is ReportedEntryRead.Erased -> ERASED
                is ReportedEntryRead.NotOpen -> NOT_OPEN
                is ReportedEntryRead.Stale -> STALE
                is ReportedEntryRead.NotAboutWords -> NOT_ABOUT_WORDS
            }
    }
}

/**
 * The port's answer and its [outcome]. [read] holds the words for [EntryTextOutcome.SHOWN], in
 * `ReportedWords`, which never prints them; [toString] names only the outcome.
 */
internal class EntryTextResult(
    val read: ReportedEntryRead,
    val outcome: EntryTextOutcome,
) {
    override fun toString(): String = "EntryTextResult($outcome)"
}

/**
 * The one door in the console to an entry's words (A5 spec §5, §6, FR-112): an `ADMIN` reads
 * the entry an open report about words names, with a typed justification and a code proved in
 * this request ([FreshStepUp]), and the read's `ENTRY_TEXT_READ` row commits in the same
 * transaction as the read and the report's `WORDS_READ` row, or none of them does.
 *
 * In order, each before the next: the role (`@PreAuthorize`, before any of this runs); the
 * justification (`ModerationNote`'s rule, [InvalidModerationNote] before the port); the proof is
 * this admin's; then the port, then the audit row. The proof was made, and its `ADMIN_STEP_UP`
 * row committed, by the caller before this transaction began, so a failed read never un-spends
 * the code. This never steps up itself and never looks at the session's window.
 *
 * Never in a log line, a metric tag, an exception message or audit `metadata`: the entry's words
 * or the justification (which is the row's `justification` and nothing else).
 */
@Service
@ConditionalOnAdminConsole
internal class ContentAccess(
    private val words: ReportedEntryText,
    private val audit: AuditTrail,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("LongParameterList") // The read's three inputs, the proof, and who asked from where.
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    fun readReportedEntry(
        reportId: UUID,
        expectedReason: String,
        justification: String,
        stepUp: FreshStepUp,
        adminId: UUID,
        from: ClientAddress?,
    ): EntryTextResult {
        val checked = ModerationNote.require(justification)
        require(stepUp.adminId == adminId) { "The step-up was proved by another admin." }
        val read = words.read(reportId, expectedReason, ModerationActor.of(adminId))
        val outcome = EntryTextOutcome.of(read)
        val entryId = read.entryId
        audit.record(
            action = AuditAction.ENTRY_TEXT_READ,
            outcome = if (outcome.succeeded) AuditOutcome.SUCCEEDED else AuditOutcome.REFUSED,
            actorId = adminId,
            targetType = if (entryId != null) ENTRY else REPORT,
            targetId = (entryId ?: reportId).toString(),
            justification = checked.text,
            metadata = mapOf("result" to outcome.name, "reportId" to reportId.toString(), "reportDriven" to true),
            clientAddress = from,
        )
        meters.counter(METRIC, "outcome", outcome.name).increment()
        log.info("content read admin={} report={} outcome={}", adminId, reportId, outcome)
        return EntryTextResult(read, outcome)
    }

    /**
     * The report page's question (A5 spec §5): null when its entry's words may be read, otherwise
     * why not. No lock, no audit row: the page's own `REPORT_READ` row records the look.
     */
    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional(readOnly = true)
    fun whyNot(reportId: UUID): WordsRefusal? = words.whyNot(reportId)

    private companion object {
        const val ENTRY = "ENTRY"
        const val REPORT = "REPORT"
        const val METRIC = "moyi.admin.content.read"
    }
}
