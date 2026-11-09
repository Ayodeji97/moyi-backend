package com.moyi.identity.web

import com.moyi.common.security.CurrentUser
import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import com.moyi.identity.domain.DeletionRequest
import com.moyi.identity.domain.UserId
import com.moyi.identity.service.AccountDeletion
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * `POST` and `DELETE /api/v1/me/deletion-request` (FR-008, doc 06 §3.2, ADR-0058). Nothing
 * here decides anything (doc 18 §4).
 *
 * `202` and not `200`: nothing is destroyed when it returns; the job carries it out after
 * the 14-day window, as `POST /bonds/{id}/deletion-request` is a `202` for the same reason
 * (ADR-0030 decision 9).
 */
@RestController
@RequestMapping("/api/v1/me/deletion-request")
internal class DeletionRequestController(
    private val deletion: AccountDeletion,
) {
    /** `account:deletion:user`, five an hour: what bounds the passwords a held session can try here. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RateLimited(RateLimitBucket.ACCOUNT_DELETION_USER)
    fun requestAccountDeletion(
        caller: CurrentUser,
        @Valid @RequestBody request: DeletionRequestBody,
    ): DeletionRequestResponse = DeletionRequestResponse.from(deletion.request(UserId(caller.id), checkNotNull(request.password)))

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelAccountDeletion(caller: CurrentUser) {
        deletion.cancel(UserId(caller.id))
    }
}

/** The account's current password, asked for again (ADR-0058 decision 1). */
internal data class DeletionRequestBody(
    // Nullable only so that a body without it is the 422 naming the field, not a 400 "could not read".
    @field:NotBlank
    @field:Size(max = MAX_PASSWORD_LENGTH)
    val password: String? = null,
) {
    override fun toString(): String = "DeletionRequestBody(password=<redacted>)"
}

/**
 * A pending deletion: when it was asked for, until when it can be called off, and the date
 * FR-008 promises it is done by. The same shape on `POST`'s `202` and on `GET /me`'s
 * `deletionRequest`, so the client draws the pending state from either.
 */
internal data class DeletionRequestResponse(
    val requestedAt: Instant,
    val cancellableUntil: Instant,
    val deleteBy: Instant,
) {
    companion object {
        fun from(request: DeletionRequest): DeletionRequestResponse =
            DeletionRequestResponse(request.requestedAt, request.cancellableUntil, request.deleteBy)
    }
}
