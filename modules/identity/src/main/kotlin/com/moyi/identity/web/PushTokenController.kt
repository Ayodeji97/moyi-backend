package com.moyi.identity.web

import com.moyi.common.security.CurrentUser
import com.moyi.identity.domain.UserId
import com.moyi.identity.service.DevicePushTokens
import com.moyi.notification.api.PushToken
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * `PUT` and `DELETE /api/v1/me/devices/current/push-token` (spec N1,
 * ADR-0044). Behind the bearer. "Current" is the device the caller's session
 * was started from; nothing in the path names it, so there is no device id a
 * caller could swap for somebody else's.
 *
 * `PUT` because the call names a state ("this device's token is …") and
 * repeating it changes nothing; it answers `204`, with nothing to say.
 */
@RestController
@RequestMapping("/api/v1/me/devices/current/push-token")
internal class PushTokenController(
    private val pushTokens: DevicePushTokens,
) {
    // Named for the API, not the class: springdoc takes the operationId from the
    // method name alone, and `register` is the sign-up route's.
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun registerPushToken(
        caller: CurrentUser,
        @Valid @RequestBody request: PushTokenRequest,
    ) {
        pushTokens.register(UserId(caller.id), caller.sessionId, PushToken(request.token))
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun forgetPushToken(caller: CurrentUser) {
        pushTokens.forget(UserId(caller.id), caller.sessionId)
    }
}

/**
 * The token as the phone's push provider gave it. Its shape is
 * [PushToken]'s to decide, and [ValidPushToken] asks it (CLAUDE.md: a rule
 * lives in one place). Not trimmed: a token with whitespace in it is not a
 * token, and is refused rather than repaired.
 */
internal data class PushTokenRequest(
    @field:NotBlank
    @field:ValidPushToken
    val token: String,
) {
    /** A push token is a secret (doc 11:25). */
    override fun toString(): String = "PushTokenRequest(token=<redacted>)"
}
