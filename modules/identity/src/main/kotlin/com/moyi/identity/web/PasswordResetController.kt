package com.moyi.identity.web

import com.moyi.identity.domain.Email
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.service.RequestPasswordReset
import com.moyi.identity.service.ResetPassword
import jakarta.validation.Valid
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
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun forgot(
        @Valid @RequestBody request: ForgotPasswordRequest,
    ) {
        requestPasswordReset.request(Email(request.email.trim()))
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    fun reset(
        @Valid @RequestBody request: ResetPasswordRequest,
    ) {
        resetPassword.reset(VerificationSecret(request.token.trim()), Password.of(request.password))
    }
}

internal data class ForgotPasswordRequest(
    @field:NotBlank
    @field:ValidEmail
    val email: String,
)

internal data class ResetPasswordRequest(
    @field:NotBlank
    @field:Size(max = MAX_TOKEN_LENGTH)
    val token: String,
    @field:NotBlank
    @field:ValidPassword
    @field:NotBreached
    val password: String,
) {
    override fun toString(): String = "ResetPasswordRequest(token=redacted)"
}
