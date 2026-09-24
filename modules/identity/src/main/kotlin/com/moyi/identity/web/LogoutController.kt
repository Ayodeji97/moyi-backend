package com.moyi.identity.web

import com.moyi.common.security.CurrentUser
import com.moyi.identity.service.LogoutUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
internal class LogoutController(
    private val logoutUser: LogoutUser,
) {
    // The status is on the mapping as well as on the entity: springdoc reads
    // the annotation and documents the 204; a bare ResponseEntity<Void> reads
    // as 200 in the generated contract (ADR-0024).
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(
        @Valid @RequestBody request: LogoutRequest,
    ): ResponseEntity<Void> {
        logoutUser.logout(request.refreshToken)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logoutAll(currentUser: CurrentUser): ResponseEntity<Void> {
        logoutUser.logoutAll(currentUser)
        return ResponseEntity.noContent().build()
    }
}

internal data class LogoutRequest(
    @field:NotBlank
    @field:Size(max = MAX_TOKEN_LENGTH)
    val refreshToken: String,
) {
    override fun toString(): String = "LogoutRequest(refreshToken=redacted)"
}
