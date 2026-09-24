package com.moyi.identity.web

import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.service.ResendVerification
import com.moyi.identity.service.VerifyEmail
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /api/v1/auth/verify-email` and `POST /api/v1/auth/resend-verification`
 * (doc 06 §3.1, FR-002).
 *
 * **Both are `POST` with the token or address in the body**, and the first
 * one in particular is not a `GET` on the link in the email — see
 * `VerificationProperties` for the mail-scanner problem that rules that out.
 *
 * **Both return an empty body.** `verify-email` because there is nothing to
 * say that the status does not; `resend-verification` because the response
 * must be identical whether the address is known, verified or invented, and
 * the only body that is identical in all three cases is none (ADR-0015's
 * reasoning, applied again).
 *
 * Nothing here does any work (doc 18 §4): HTTP in, service call, HTTP out.
 */
@RestController
@RequestMapping("/api/v1/auth")
internal class VerificationController(
    private val verifyEmail: VerifyEmail,
    private val resendVerification: ResendVerification,
) {
    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.OK)
    fun verify(
        @Valid @RequestBody request: VerifyEmailRequest,
    ) {
        verifyEmail.verify(VerificationSecret(request.token.trim()))
    }

    /**
     * 202, not 200: the request is accepted, and whether anything follows is
     * deliberately not disclosed. Twenty an hour per address (ADR-0023): this
     * endpoint sends email to whoever asks, and the per-email cooldown in
     * the service bounds nothing an attacker cannot vary.
     */
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RateLimited(RateLimitBucket.AUTH_RESEND_IP)
    fun resend(
        @Valid @RequestBody request: ResendVerificationRequest,
    ) {
        resendVerification.resend(Email(request.email.trim()))
    }
}

/**
 * The token is bounded, not shape-checked: what a person presents is
 * whatever their mail client handed them, and the right answer to a mangled
 * one is 422 "not recognised" from the service, not a validation error that
 * describes the format we expect. The bound exists so that a megabyte cannot
 * be hashed on request.
 */
internal data class VerifyEmailRequest(
    @field:NotBlank
    @field:Size(max = MAX_TOKEN_LENGTH)
    val token: String,
) {
    override fun toString(): String = "VerifyEmailRequest(token=redacted)"
}

internal data class ResendVerificationRequest(
    @field:NotBlank
    @field:ValidEmail
    val email: String,
)

/** Generous against a 43-character token, tight against a payload. */
internal const val MAX_TOKEN_LENGTH = 256
