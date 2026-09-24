package com.moyi.identity.service

import com.moyi.common.web.NotFoundException
import com.moyi.identity.domain.Session
import com.moyi.identity.domain.UserId
import com.moyi.identity.infra.database.DeviceStore
import com.moyi.identity.infra.database.RefreshTokenStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * `GET /auth/sessions` (FR-007): the caller's live sign-ins.
 *
 * A session is a live refresh-token family — there is exactly one live token
 * per live family, so the live tokens *are* the list. `lastSeenAt` is that
 * token's `issuedAt`: every rotation mints a new one, so it is the last time
 * the client refreshed, with no separate bookkeeping to drift. `createdAt`
 * is the family's first token. `current` is whether the caller's access
 * token was minted for this family (`sid`).
 */
@Service
internal class ListSessions(
    private val tokens: RefreshTokenStore,
    private val devices: DeviceStore,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun forUser(
        userId: UserId,
        currentSessionId: UUID?,
    ): List<Session> {
        val now = clock.instant()
        val starts = tokens.familyStartsOf(userId)
        val ownDevices = devices.findAllOf(userId)
        return tokens
            .findLiveOf(userId, now)
            .map { token ->
                Session(
                    id = token.familyId,
                    device = token.deviceId?.let(ownDevices::get),
                    createdAt = starts.getValue(token.familyId),
                    lastSeenAt = token.issuedAt,
                    current = token.familyId == currentSessionId,
                )
            }.sortedByDescending { it.lastSeenAt }
    }
}

/**
 * `DELETE /auth/sessions/{id}` (FR-007): ends one sign-in. Revoking the
 * family is what `logout` does with the refresh token in hand; here the
 * caller names the family instead, and the `userId` predicate in the
 * `UPDATE` is the authorisation — a family that is not theirs, does not
 * exist, or is already over changes no row, and no row is 404 (doc 06 §2:
 * never a 403 that confirms an id). Under the per-user sessions lock like
 * every other session mutation (ADR-0021).
 */
@Service
internal class RevokeSession(
    private val tokens: RefreshTokenStore,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @throws SessionNotFoundException no live session with that id belongs to the caller */
    fun revoke(
        userId: UserId,
        sessionId: UUID,
    ) {
        val now = clock.instant()
        val revoked =
            transactions.execute {
                tokens.lockSessionsOf(userId)
                tokens.revokeFamilyOf(userId, sessionId, now)
            }!!
        if (!revoked) throw SessionNotFoundException()
        log.info("Session {} ended by user {}", sessionId, userId.value)
    }
}

/**
 * No live session of the caller's with that id — theirs and ended, somebody
 * else's, or invented. One answer for all three (doc 06 §2), through the
 * shared `NotFoundException` so the catch-all writes the 404.
 */
internal class SessionNotFoundException : NotFoundException("That session was not found.")
