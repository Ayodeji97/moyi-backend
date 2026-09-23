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
        val result = loginUser.login(LoginCommand(request.email, request.password, request.deviceInfo))
        return LoginResponse(
            accessToken = result.accessToken.token,
            expiresIn = result.accessToken.expiresIn,
            refreshToken = result.refreshToken.secret,
            user = UserResponse.from(result.user),
        )
    }
}

internal data class LoginRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val password: String,
    @field:jakarta.validation.constraints.Size(max = 200) val deviceInfo: String? = null,
) {
    override fun toString(): String = "LoginRequest(email=$email, deviceInfo=$deviceInfo)"
}

internal data class LoginResponse(
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String,
    val user: UserResponse,
) {
    override fun toString(): String = "LoginResponse(accessToken=redacted, refreshToken=redacted, user=$user)"
}
