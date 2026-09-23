package com.moyi.identity.web

import com.moyi.common.security.CurrentUser
import com.moyi.identity.service.LogoutUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
internal class LogoutController(
    private val logoutUser: LogoutUser,
) {
    @PostMapping("/logout")
    fun logout(
        @Valid @RequestBody request: LogoutRequest,
    ): ResponseEntity<Void> {
        logoutUser.logout(request.refreshToken)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/logout-all")
    fun logoutAll(currentUser: CurrentUser): ResponseEntity<Void> {
        logoutUser.logoutAll(currentUser)
        return ResponseEntity.noContent().build()
    }
}

internal data class LogoutRequest(
    @field:NotBlank val refreshToken: String,
) {
    override fun toString(): String = "LogoutRequest(refreshToken=redacted)"
}
