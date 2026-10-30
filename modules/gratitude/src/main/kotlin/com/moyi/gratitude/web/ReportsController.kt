package com.moyi.gratitude.web

import com.moyi.common.security.CurrentUser
import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.common.web.idempotency.Idempotent
import com.moyi.gratitude.service.ReportEntry
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

/**
 * `POST /api/v1/entries/{entryId}/report` (FR-100, ADR-0053): a member reports
 * their partner's entry, with its photo and voice note, to a person. Behind the
 * bearer, as everything outside the public auth endpoints is.
 *
 * **By entry id, the shape of the reaction routes beside it** (ADR-0037), not
 * doc 06's `POST /reports {targetType, targetId, …}`: the one target built is
 * an entry, its gate is the entry's, and an entry-scoped path keeps the one
 * `404` for everything the caller was never shown. A member report (doc 26's
 * other target) will be a route of its own when it is built.
 *
 * **`201` with the report's id**, and from that moment the entry's photo and
 * voice note are held back from the caller (shown to them as processing) and
 * shown to the author as not shared, until a person decides. The author is
 * told nothing else, ever (doc 26 §2.2).
 *
 * **`Idempotency-Key` required** (doc 06:16): a report creates something, and
 * a retry after a lost answer must not file it twice. **`reports:create:user`,
 * ten a day** (ADR-0053 decision 9).
 *
 * The method name is the API's `operationId`.
 */
@RestController
@RequestMapping("/api/v1/entries")
internal class ReportsController(
    private val reports: ReportEntry,
) {
    // @ResponseStatus beside the ResponseEntity's 201: springdoc reads the annotation (EntriesController.submitEntry).
    @Idempotent
    @RateLimited(RateLimitBucket.REPORTS_CREATE_USER)
    @PostMapping("/{entryId}/report")
    @ResponseStatus(HttpStatus.CREATED)
    fun reportEntry(
        caller: CurrentUser,
        @PathVariable entryId: String,
        @Valid @RequestBody request: ReportRequest,
        http: HttpServletRequest,
    ): ResponseEntity<ReportResponse> {
        val filed =
            reports.report(
                caller.id,
                entryIdOrNotFound(entryId),
                request.reportReason(),
                request.reportDetail(),
                IdempotencyInterceptor.requestOf(http),
            )
        val response = ResponseEntity.status(HttpStatus.CREATED)
        if (filed.replayed) response.header(IdempotencyInterceptor.REPLAYED_HEADER, "true")
        return response.body(ReportResponse.of(filed.report))
    }
}
