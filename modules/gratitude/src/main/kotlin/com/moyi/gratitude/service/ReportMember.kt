package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.common.core.IdGenerator
import com.moyi.common.web.NotFoundException
import com.moyi.common.web.idempotency.IdempotentExecution
import com.moyi.common.web.idempotency.IdempotentRequest
import com.moyi.common.web.idempotency.IdempotentResult
import com.moyi.common.web.idempotency.ResultKind
import com.moyi.gratitude.domain.MemberReport
import com.moyi.gratitude.domain.ReportDetail
import com.moyi.gratitude.domain.ReportReason
import com.moyi.gratitude.infra.database.EntryReports
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What `POST /bonds/{bondId}/members/other/report` answers: the report as its reporter is told of it, and whether this was a replay. */
internal data class FiledMemberReport(
    val report: MemberReport,
    val replayed: Boolean,
)

/**
 * `POST /bonds/{bondId}/members/other/report` (FR-100's member half, ADR-0053
 * as amended for #127): a member reports the other member of their bond to a
 * person.
 *
 * **Who may: any member of the bond, in any state, about the other member
 * row.** The bond's guard first ([BondAccess.membershipOf]: a non-member, or
 * no such bond, is the one `404`), then the other row
 * ([BondAccess.partnerMemberIdOf]: a bond that has never had a second member
 * is that same `404`). **Not refused on an ended bond, or after a block, or
 * after either has left**: a report is never contact (doc 26 §2.2), and a
 * member who has just blocked their partner may need it most. No bond lock:
 * nothing about the bond is changed, and nothing it decides depends on a state
 * a concurrent ending could change.
 *
 * **Then one transaction holds the key and the report**, as `ReportEntry`'s
 * does, with nothing for `media` to hold: a person has no photo. A replay
 * re-reads, authorised as a read of the report: the report the key names, if
 * the caller made it.
 *
 * **What the reported member is told: nothing, and nothing they read moves.**
 * The only rows written are the report and the key; no event, no push, no
 * column of anything the reported member's routes read (`MemberReportsTest`
 * compares their bytes and ETags before and after).
 *
 * Nothing of the request is logged here: not the detail, not the reason.
 */
@Service
@Suppress("LongParameterList") // A service's collaborators, each injected by Spring.
internal class ReportMember(
    private val access: BondAccess,
    private val reports: EntryReports,
    private val execution: IdempotentExecution,
    private val transactions: TransactionTemplate,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /**
     * @param idempotency the request's `Idempotency-Key`, as `IdempotencyInterceptor` prepared it
     * @throws NotFoundException the caller is not a member of [bondId], there is no such bond, or it has never had
     *   a second member: one answer; on a replay, the key names no report of theirs
     * @throws com.moyi.common.web.idempotency.IdempotencyKeyInFlightException the key's first request has not finished
     * @throws com.moyi.common.web.idempotency.IdempotencyKeyReusedException the key was first used for a different request
     */
    fun report(
        userId: UUID,
        bondId: UUID,
        reason: ReportReason,
        detail: ReportDetail?,
        idempotency: IdempotentRequest,
    ): FiledMemberReport {
        require(idempotency.userId == userId) { "the Idempotency-Key was prepared for a different caller than the one reporting" }
        val membership = access.membershipOf(userId, bondId)
        val reported = access.partnerMemberIdOf(membership)
        // Truncated once, to what `timestamptz` keeps (CLAUDE.md).
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val filed =
            transactions.execute {
                val outcome =
                    execution.once(idempotency) {
                        val report = MemberReport(ids.opaque(), bondId, membership.memberId, reported, reason)
                        reports.insert(report, detail, now)
                        IdempotentResult(report, report.id, ResultKind.REPORT, HttpStatus.CREATED.value())
                    }
                FiledMemberReport(outcome.value ?: replay(userId, outcome.resultId), outcome.wasReplayed)
            }
        return checkNotNull(filed) { "report's transaction produces a FiledMemberReport unless it threw" }
    }

    /** `ReportEntry.replay`'s rule: the report the key filed, if the caller is its reporter; anything else, the bond's `404`. */
    private fun replay(
        userId: UUID,
        reportId: UUID,
    ): MemberReport {
        val report = reports.findMember(reportId)
        val reporter =
            report?.let {
                try {
                    access.membershipOf(userId, it.bondId).memberId
                } catch (_: NotFoundException) {
                    null
                }
            }
        return report?.takeIf { reporter == it.reporterMemberId } ?: throw NotFoundException("That bond was not found.")
    }
}
