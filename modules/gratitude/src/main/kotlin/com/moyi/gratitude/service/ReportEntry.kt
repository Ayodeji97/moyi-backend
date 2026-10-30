package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.common.core.IdGenerator
import com.moyi.common.web.NotFoundException
import com.moyi.common.web.idempotency.IdempotentExecution
import com.moyi.common.web.idempotency.IdempotentRequest
import com.moyi.common.web.idempotency.IdempotentResult
import com.moyi.common.web.idempotency.ResultKind
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryReport
import com.moyi.gratitude.domain.ReportDetail
import com.moyi.gratitude.domain.ReportReason
import com.moyi.gratitude.domain.Reporting
import com.moyi.gratitude.domain.canBeReportedBy
import com.moyi.gratitude.infra.database.EntryReports
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.media.api.MediaReports
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What `POST /entries/{entryId}/report` answers: the report as its reporter is told of it, and whether this was a replay. */
internal data class FiledReport(
    val report: EntryReport,
    val replayed: Boolean,
)

/**
 * `POST /entries/{entryId}/report` (FR-100, ADR-0053): a member reports their
 * partner's entry to a person, and from that moment its photo and voice note
 * are held back from them pending review.
 *
 * **Who may report what is the read gate's answer for the caller**
 * ([com.moyi.gratitude.domain.Entry.canBeReportedBy]): everything the caller
 * was never shown is the one [EntryNotFoundException], their own entry is
 * [ReportOnOwnEntryException], and the partner's entry, read in full or as a
 * tombstone, is reported. Asked before the transaction, as `ReactToEntry`
 * asks it, with the membership resolved after the entry is loaded
 * ([entryAndMembershipNow]). **Not refused on an ended bond**: a report is
 * never contact (doc 26 §2.2), and a member who has just blocked their
 * partner may need it most.
 *
 * **Then one transaction holds the key, the report and the hold** (spec
 * §5.4's shape, as `SubmitEntry`'s): [IdempotentExecution.once], the row,
 * and `media`'s [MediaReports.holdFor] keyed by the entry. A report never
 * commits without its hold, nor a hold without its report. An entry erased
 * between the gate and the hold has nothing attached, and the report stands
 * with nothing held: what was shown may still be what is reported.
 *
 * **A replay re-reads; it never files a second report**, and is authorised
 * as a read of the report: the report the key names, if the caller made it.
 *
 * **What the author is told: nothing of the report.** No push, no event, no
 * field. What they can see is what `media` shows them of a held-back photo,
 * "not shared", exactly as for one moderation holds back (ADR-0051 decision
 * 4; ADR-0053 decision 5 has why that is the accepted cost).
 *
 * Nothing of the request is logged here: not the detail, not the reason.
 */
@Service
@Suppress("LongParameterList") // A service's collaborators, each injected by Spring.
internal class ReportEntry(
    private val access: BondAccess,
    private val entries: EntryStore,
    private val joining: ReconcileJoiningDay,
    private val reports: EntryReports,
    private val media: MediaReports,
    private val execution: IdempotentExecution,
    private val transactions: TransactionTemplate,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /**
     * @param idempotency the request's `Idempotency-Key`, as `IdempotencyInterceptor` prepared it
     * @throws EntryNotFoundException the entry was never shown to the caller, for any reason; on a replay, the key
     *   names no report of theirs
     * @throws ReportOnOwnEntryException the caller wrote it
     * @throws com.moyi.common.web.idempotency.IdempotencyKeyInFlightException the key's first request has not finished
     * @throws com.moyi.common.web.idempotency.IdempotencyKeyReusedException the key was first used for a different request
     */
    fun report(
        userId: UUID,
        entryId: EntryId,
        reason: ReportReason,
        detail: ReportDetail?,
        idempotency: IdempotentRequest,
    ): FiledReport {
        require(idempotency.userId == userId) { "the Idempotency-Key was prepared for a different caller than the one reporting" }
        val (entry, membership) = entryAndMembershipNow(access, entries, joining, userId, entryId)
        when (entry.canBeReportedBy(membership.asReader())) {
            Reporting.NEVER_SHOWN -> throw EntryNotFoundException()
            Reporting.OWN_ENTRY -> throw ReportOnOwnEntryException()
            Reporting.IN_FULL, Reporting.ERASED -> Unit
        }
        // Truncated once, to what `timestamptz` keeps (CLAUDE.md).
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val filed =
            transactions.execute {
                val outcome =
                    execution.once(idempotency) {
                        val report = EntryReport(ids.opaque(), entryId, entry.bondId, membership.memberId, reason)
                        reports.insert(report, detail, now)
                        media.holdFor(entryId.value, report.id)
                        IdempotentResult(report, report.id, ResultKind.REPORT, HttpStatus.CREATED.value())
                    }
                FiledReport(outcome.value ?: replay(userId, outcome.resultId), outcome.wasReplayed)
            }
        return checkNotNull(filed) { "report's transaction produces a FiledReport unless it threw" }
    }

    /**
     * The report a key already filed, if the caller filed it: the reporter's
     * member id in the report's bond is theirs. Anything else is the one
     * `404`, which a key this class wrote cannot reach.
     */
    private fun replay(
        userId: UUID,
        reportId: UUID,
    ): EntryReport {
        val report = reports.find(reportId)
        val reporter =
            report?.let {
                try {
                    access.membershipOf(userId, it.bondId).memberId
                } catch (_: NotFoundException) {
                    null
                }
            }
        return report?.takeIf { reporter == it.reporterMemberId } ?: throw EntryNotFoundException()
    }
}
