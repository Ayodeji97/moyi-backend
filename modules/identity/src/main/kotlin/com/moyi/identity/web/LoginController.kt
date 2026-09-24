package com.moyi.identity.web

import com.moyi.identity.service.LoginCommand
import com.moyi.identity.service.LoginUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
internal class LoginController(
    private val loginUser: LoginUser,
) {
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
    ): LoginResponse {
        val result = loginUser.login(LoginCommand(request.email, request.password, request.deviceInfo))
        return LoginResponse(
            accessToken = result.tokens.accessToken.token,
            expiresIn = result.tokens.accessToken.expiresIn,
            refreshToken = result.tokens.refreshToken.secret,
            user = UserResponse.from(result.user),
        )
    }
}

/**
 * Bounded, not shape-checked: a malformed address takes the unknown path
 * (same 401, same cost) rather than a 422 that says "that is not even an
 * address". The bounds exist so a megabyte cannot be verified on request;
 * nothing valid is longer than these and a 422 for length reveals nothing
 * about accounts.
 */
internal data class LoginRequest(
    @field:NotBlank
    @field:Size(max = MAX_EMAIL_LENGTH)
    val email: String,
    @field:NotBlank
    @field:Size(max = MAX_PASSWORD_LENGTH)
    val password: String,
    /** The client's free-text description of itself; see `LoginCommand`. */
    @field:Size(max = MAX_DEVICE_INFO_LENGTH)
    val deviceInfo: String? = null,
) {
    /** The address is personal data (doc 11 NFR-044) and the password is a secret; neither prints. */
    override fun toString(): String = "LoginRequest(email=<redacted>, password=<redacted>, deviceInfo=$deviceInfo)"
}

internal data class LoginResponse(
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String,
    val user: UserResponse,
) {
    override fun toString(): String = "LoginResponse(accessToken=redacted, refreshToken=redacted, user=$user)"
}

/** RFC 5321's path maximum, the same bound `Email` enforces. */
internal const val MAX_EMAIL_LENGTH = 254

/** `Password.MAX_OCTETS`: nothing longer can be a valid password, so nothing longer is worth verifying. */
internal const val MAX_PASSWORD_LENGTH = 512

internal const val MAX_DEVICE_INFO_LENGTH = 200
