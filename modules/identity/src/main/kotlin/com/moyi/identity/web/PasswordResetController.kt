package com.moyi.identity.web

import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.VerificationCode
import com.moyi.identity.service.RequestPasswordReset
import com.moyi.identity.service.ResetPassword
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
internal class PasswordResetController(
    private val requestPasswordReset: RequestPasswordReset,
    private val resetPassword: ResetPassword,
) {
    /** Twenty an hour per address (ADR-0023), beside the three-an-hour-per-email bucket in the service. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RateLimited(RateLimitBucket.AUTH_RESET_IP)
    fun forgot(
        @Valid @RequestBody request: ForgotPasswordRequest,
    ) {
        requestPasswordReset.request(Email(request.email.trim()))
    }

    /** The token from the link, or `{email, code}` (#135); the same per-address bucket as `verify-email`. */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    @RateLimited(RateLimitBucket.AUTH_CODE_IP)
    fun reset(
        @Valid @RequestBody request: ResetPasswordRequest,
    ) {
        val password = Password.of(request.password)
        when (val presented = request.presented()) {
            is PresentedCredential.Link -> resetPassword.reset(presented.secret, password)
            is PresentedCredential.Code -> resetPassword.reset(presented.email, presented.code, password)
        }
    }

    /**
     * Whether a typed reset code is right, so the app can say so before the new password is
     * typed (#169, ADR-0022 amended 2026-11-10). 204 and nothing spent for a right code; the
     * reset's one 422 for every refusal. A wrong check is a try at the same code, and both
     * buckets are the reset's: this per-address one, and ten a day per address in the service.
     */
    @PostMapping("/reset-password/check")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RateLimited(RateLimitBucket.AUTH_CODE_IP)
    fun checkResetCode(
        @Valid @RequestBody request: ResetCodeCheckRequest,
    ) {
        resetPassword.check(Email(request.email.trim()), VerificationCode(request.code.trim()))
    }
}

/** The address and the six digits from the reset email; no token (a link needs no check) and no password. */
internal data class ResetCodeCheckRequest(
    @field:NotBlank
    @field:ValidEmail
    val email: String,
    @field:NotBlank
    @field:ValidVerificationCode
    val code: String,
) {
    override fun toString(): String = "ResetCodeCheckRequest(code=redacted)"
}

internal data class ForgotPasswordRequest(
    @field:NotBlank
    @field:ValidEmail
    val email: String,
)

/** The token, or the address and the code, as [VerifyEmailRequest]; and the new password. */
internal data class ResetPasswordRequest(
    @field:Size(max = MAX_TOKEN_LENGTH)
    val token: String? = null,
    @field:ValidEmail
    val email: String? = null,
    @field:ValidVerificationCode
    val code: String? = null,
    @field:NotBlank
    @field:ValidPassword
    @field:NotBreached
    val password: String,
) {
    @Suppress("unused") // Read by Bean Validation.
    @get:AssertTrue(message = PresentedCredential.FORM_MESSAGE)
    private val tokenOrEmailAndCode: Boolean
        get() = PresentedCredential.formOf(token, email, code) != null

    fun presented(): PresentedCredential = PresentedCredential.of(token, email, code)

    override fun toString(): String = "ResetPasswordRequest(token=redacted, code=redacted)"
}
