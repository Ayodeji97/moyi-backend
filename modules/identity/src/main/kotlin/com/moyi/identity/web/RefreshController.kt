package com.moyi.identity.web

import com.moyi.identity.service.RotateRefreshToken
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /api/v1/auth/refresh` (doc 06 §3.1). Same response shape as login,
 * because the client treats the two identically: store the pair, continue.
 * The failures are the interesting part and live in `RotateRefreshToken`.
 */
@RestController
@RequestMapping("/api/v1/auth")
internal class RefreshController(
    private val rotate: RotateRefreshToken,
) {
    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
    ): LoginResponse {
        val result = rotate.rotate(request.refreshToken)
        return LoginResponse(
            accessToken = result.tokens.accessToken.token,
            expiresIn = result.tokens.accessToken.expiresIn,
            refreshToken = result.tokens.refreshToken.secret,
            user = UserResponse.from(result.user),
        )
    }
}

internal data class RefreshRequest(
    @field:NotBlank
    @field:Size(max = MAX_TOKEN_LENGTH)
    val refreshToken: String,
) {
    override fun toString(): String = "RefreshRequest(refreshToken=redacted)"
}
