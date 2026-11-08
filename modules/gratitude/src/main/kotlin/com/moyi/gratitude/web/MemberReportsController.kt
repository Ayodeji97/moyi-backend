package com.moyi.gratitude.web

import com.moyi.common.security.CurrentUser
import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import com.moyi.common.web.NotFoundException
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.common.web.idempotency.Idempotent
import com.moyi.gratitude.domain.MemberReport
import com.moyi.gratitude.domain.ReportReason
import com.moyi.gratitude.service.ReportMember
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `POST /api/v1/bonds/{bondId}/members/other/report` (FR-100's member half,
 * ADR-0053 as amended for #127): a member reports the other member of their
 * bond to a person. Doc 26 §4's "the member" row; T-09's "how they're treating
 * me", which is about no one entry.
 *
 * **No member id in the path**, as `/members/me/settings` has none: a bond has
 * two members, and the caller can only ever mean the other. The body is the
 * entry route's [ReportRequest], the same five reasons.
 *
 * **`201` with `{id, reason}`**; the reporter is told a person will read it,
 * never what was decided. The reported member is told nothing and shown
 * nothing different, ever (doc 26 §2.2). Allowed on an ended bond, after a
 * block, after either has left. A bond the caller is not in, and one that
 * has never had a second member, are the one `404`.
 *
 * **`Idempotency-Key` required**, and **`reports:create:user`**: the entry
 * route's bucket, ten a day across both targets (ADR-0053 decision 9).
 *
 * The method name is the API's `operationId`.
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class MemberReportsController(
    private val reports: ReportMember,
) {
    // @ResponseStatus beside the ResponseEntity's 201: springdoc reads the annotation (EntriesController.submitEntry).
    @Idempotent
    @RateLimited(RateLimitBucket.REPORTS_CREATE_USER)
    @PostMapping("/{bondId}/members/other/report")
    @ResponseStatus(HttpStatus.CREATED)
    fun reportMember(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @Valid @RequestBody request: ReportRequest,
        http: HttpServletRequest,
    ): ResponseEntity<MemberReportResponse> {
        val id = runCatching { UUID.fromString(bondId) }.getOrElse { throw NotFoundException("That bond was not found.") }
        val filed =
            reports.report(
                caller.id,
                id,
                request.reportReason(),
                request.reportDetail(),
                IdempotencyInterceptor.requestOf(http),
            )
        val response = ResponseEntity.status(HttpStatus.CREATED)
        if (filed.replayed) response.header(IdempotencyInterceptor.REPLAYED_HEADER, "true")
        return response.body(MemberReportResponse.of(filed.report))
    }
}

/** What the reporter is told of a member report: its id and the reason they gave. Nothing of who, or of what is decided. */
internal data class MemberReportResponse(
    val id: UUID,
    val reason: ReportReason,
) {
    companion object {
        fun of(report: MemberReport): MemberReportResponse = MemberReportResponse(report.id, report.reason)
    }
}
