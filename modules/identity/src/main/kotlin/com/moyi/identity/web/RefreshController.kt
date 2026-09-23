package com.moyi.identity.web

import com.moyi.identity.service.RefreshTokens
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
internal class RefreshController(
    private val refreshTokens: RefreshTokens,
) {
    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
    ): LoginResponse {
        val result = refreshTokens.rotate(request.refreshToken)
        return LoginResponse(
            accessToken = result.accessToken.token,
            expiresIn = result.accessToken.expiresIn,
            refreshToken = result.refreshToken.secret,
            user = UserResponse.from(result.user),
        )
    }
}

internal data class RefreshRequest(
    @field:NotBlank val refreshToken: String,
) {
    override fun toString(): String = "RefreshRequest(refreshToken=redacted)"
}
