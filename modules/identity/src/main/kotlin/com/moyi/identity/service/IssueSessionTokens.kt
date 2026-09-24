package com.moyi.identity.service

import com.moyi.common.core.IdGenerator
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.security.IssuedAccessToken
import com.moyi.identity.domain.Device
import com.moyi.identity.domain.DeviceDescription
import com.moyi.identity.domain.RefreshToken
import com.moyi.identity.domain.TokenGenerator
import com.moyi.identity.domain.UserId
import com.moyi.identity.infra.database.DeviceStore
import com.moyi.identity.infra.database.RefreshTokenStore
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** The one moment a refresh secret exists in plaintext on the server: between minting and the response. */
internal data class IssuedRefreshToken(
    val secret: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "IssuedRefreshToken(secret=<redacted>, expiresAt=$expiresAt)"
}

/** A family just started: its id, which the access token will carry as `sid`, and its first refresh token. */
internal data class NewFamily(
    val familyId: UUID,
    val refreshToken: IssuedRefreshToken,
)

/** What a sign-in or a rotation hands back: the short-lived JWT and the long-lived opaque secret (FR-003). */
internal data class SessionTokens(
    val accessToken: IssuedAccessToken,
    val refreshToken: IssuedRefreshToken,
)

/**
 * Mints session tokens. The refresh token (doc 09 §3) is 256 bits of CSPRNG
 * output, returned once and stored only as its SHA-256, thirty days, in a
 * *family*; the access token is E1's RS256 JWT.
 *
 * A family is the chain of tokens a single sign-in produces as it rotates.
 * Login starts a new one ([newFamily]); `RotateRefreshToken` extends it with
 * [successor], and a reuse anywhere in the chain revokes all of it. The
 * family id is an opaque UUID, never a time-ordered one: nothing should be
 * able to infer login order from it.
 *
 * The refresh half joins the caller's transaction and opens none of its own,
 * like `RequestVerification`. The access half ([accessTokenFor]) touches no
 * database and is called after the commit.
 */
@Component
internal class IssueSessionTokens(
    private val tokens: RefreshTokenStore,
    private val devices: DeviceStore,
    private val ids: IdGenerator,
    private val secrets: TokenGenerator,
    private val accessTokens: AccessTokenIssuer,
) {
    /**
     * A new family for a fresh sign-in, on the device the client described
     * (FR-007) — a `devices` row per sign-in, see `Device`. Inside the
     * caller's transaction.
     */
    fun newFamily(
        userId: UserId,
        device: DeviceDescription?,
        now: Instant,
    ): NewFamily {
        val deviceId =
            device?.let {
                val id = ids.timeOrdered()
                devices.insert(
                    Device(
                        id = id,
                        userId = userId,
                        platform = it.platform,
                        appVersion = it.appVersion,
                        osVersion = it.osVersion,
                        lastSeenAt = now,
                    ),
                )
                id
            }
        val familyId = ids.opaque()
        return NewFamily(familyId, mint(userId, familyId, deviceId, now))
    }

    /** The access token names its family (`sid`), which is how the sessions list marks the current one. */
    fun accessTokenFor(
        userId: UserId,
        sessionId: UUID,
    ): IssuedAccessToken = accessTokens.issue(userId.value, sessionId)

    /** FR-007: a rotation is the family's device being seen again. Inside the caller's transaction. */
    fun deviceSeen(
        deviceId: UUID,
        now: Instant,
    ) = devices.touch(deviceId, now)

    /** The per-user sessions lock; see `RefreshTokenStore.lockSessionsOf`. Inside the caller's transaction. */
    fun lockSessionsOf(userId: UserId) = tokens.lockSessionsOf(userId)

    /** The next link in an existing family, for a rotation. */
    fun successor(
        previous: RefreshToken,
        now: Instant,
    ): Pair<RefreshToken, IssuedRefreshToken> {
        val secret = secrets.verificationSecret()
        val token =
            RefreshToken.issue(
                id = ids.timeOrdered(),
                userId = previous.userId,
                familyId = previous.familyId,
                secret = secret,
                now = now,
                deviceId = previous.deviceId,
            )
        return token to IssuedRefreshToken(secret.value, token.expiresAt)
    }

    private fun mint(
        userId: UserId,
        familyId: UUID,
        deviceId: UUID?,
        now: Instant,
    ): IssuedRefreshToken {
        val secret = secrets.verificationSecret()
        val token =
            RefreshToken.issue(
                id = ids.timeOrdered(),
                userId = userId,
                familyId = familyId,
                secret = secret,
                now = now,
                deviceId = deviceId,
            )
        tokens.insert(token)
        return IssuedRefreshToken(secret.value, token.expiresAt)
    }
}
