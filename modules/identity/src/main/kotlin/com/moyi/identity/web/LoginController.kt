package com.moyi.identity.web

import com.moyi.identity.service.LoginCommand
import com.moyi.identity.service.LoginUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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
        val result = loginUser.login(LoginCommand(request.email, request.password))
        return LoginResponse(
            accessToken = result.accessToken.token,
            expiresIn = result.accessToken.expiresIn,
            user = UserResponse.from(result.user),
        )
    }
}

internal data class LoginRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val password: String,
    /** Accepted now so clients can send device metadata without changing the wire shape. */
    val deviceInfo: String? = null,
) {
    override fun toString(): String = "LoginRequest(email=$email, deviceInfo=$deviceInfo)"
}

internal data class LoginResponse(
    val accessToken: String,
    val expiresIn: Long,
    val user: UserResponse,
)
