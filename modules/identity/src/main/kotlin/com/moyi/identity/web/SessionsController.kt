package com.moyi.identity.web

import com.moyi.common.security.CurrentUser
import com.moyi.identity.domain.Device
import com.moyi.identity.domain.DevicePlatform
import com.moyi.identity.domain.Session
import com.moyi.identity.domain.UserId
import com.moyi.identity.service.ListSessions
import com.moyi.identity.service.RevokeSession
import com.moyi.identity.service.SessionNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * `GET /api/v1/auth/sessions` and `DELETE /api/v1/auth/sessions/{id}`
 * (doc 06 §3.1, FR-007). Both behind the bearer; neither is in the chain's
 * public list. Nothing here does any work (doc 18 §4).
 *
 * The id is taken as text and parsed here rather than as a `UUID` parameter:
 * a value that is not a UUID cannot name a session, and the answer to that
 * is the same 404 as to a UUID that names nobody's — not a 400 that says
 * the shape was wrong.
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
internal class SessionsController(
    private val listSessions: ListSessions,
    private val revokeSession: RevokeSession,
) {
    @GetMapping
    fun list(caller: CurrentUser): SessionsResponse =
        SessionsResponse(listSessions.forUser(UserId(caller.id), caller.sessionId).map(SessionResponse::from))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        caller: CurrentUser,
        @PathVariable id: String,
    ) {
        val sessionId = runCatching { UUID.fromString(id) }.getOrElse { throw SessionNotFoundException() }
        revokeSession.revoke(UserId(caller.id), sessionId)
    }
}

internal data class SessionsResponse(
    val sessions: List<SessionResponse>,
)

/** One live sign-in. No token, no hash, nothing a holder of this could use to become that session. */
internal data class SessionResponse(
    val id: UUID,
    val device: DeviceResponse?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val current: Boolean,
) {
    companion object {
        fun from(session: Session): SessionResponse =
            SessionResponse(
                id = session.id,
                device = session.device?.let(DeviceResponse::from),
                createdAt = session.createdAt,
                lastSeenAt = session.lastSeenAt,
                current = session.current,
            )
    }
}

internal data class DeviceResponse(
    val platform: DevicePlatform,
    val appVersion: String,
    val osVersion: String,
) {
    companion object {
        fun from(device: Device): DeviceResponse = DeviceResponse(device.platform, device.appVersion, device.osVersion)
    }
}
