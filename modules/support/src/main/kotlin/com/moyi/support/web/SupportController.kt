package com.moyi.support.web

import com.moyi.common.security.CurrentUser
import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.common.web.idempotency.Idempotent
import com.moyi.support.service.FileSupportTicket
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /api/v1/support/messages` (FR-102, ADR-0057): a signed-in member writes to a person. Behind
 * the bearer, as everything outside the public auth endpoints is: a signed-out person is not served
 * here (decision 1).
 *
 * **`201` with the ticket's id and when it arrived**, and once it commits an acknowledgement email to
 * the account's confirmed address, which never repeats what was written.
 *
 * **`Idempotency-Key` optional** (decision 4): a client that sends one is not filed twice for a retry
 * after a lost answer; one that does not still files. **`support:create:user`, five a day** (doc 06 §4).
 *
 * The method name is the API's `operationId`.
 */
@RestController
@RequestMapping("/api/v1/support")
internal class SupportController(
    private val filing: FileSupportTicket,
) {
    // @ResponseStatus beside the ResponseEntity's 201: springdoc reads the annotation (EntriesController.submitEntry).
    @Idempotent(required = false)
    @RateLimited(RateLimitBucket.SUPPORT_CREATE_USER)
    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    fun sendSupportMessage(
        caller: CurrentUser,
        @Valid @RequestBody request: SupportMessageRequest,
        http: HttpServletRequest,
    ): ResponseEntity<SupportMessageResponse> {
        val filed =
            filing.file(
                caller.id,
                request.supportSubject(),
                request.supportBody(),
                request.supportReference(),
                IdempotencyInterceptor.requestOrNull(http),
            )
        val response = ResponseEntity.status(HttpStatus.CREATED)
        if (filed.replayed) response.header(IdempotencyInterceptor.REPLAYED_HEADER, "true")
        return response.body(SupportMessageResponse(filed.ticket.id, filed.ticket.createdAt))
    }
}
