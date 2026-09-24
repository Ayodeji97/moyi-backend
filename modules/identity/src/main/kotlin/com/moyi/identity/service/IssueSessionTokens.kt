package com.moyi.identity.service

import com.moyi.common.core.IdGenerator
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.security.IssuedAccessToken
import com.moyi.identity.domain.RefreshToken
import com.moyi.identity.domain.TokenGenerator
import com.moyi.identity.domain.UserId
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
    private val ids: IdGenerator,
    private val secrets: TokenGenerator,
    private val accessTokens: AccessTokenIssuer,
) {
    /** A new family for a fresh sign-in. Inside the caller's transaction. */
    fun newFamily(
        userId: UserId,
        deviceInfo: String?,
        now: Instant,
    ): IssuedRefreshToken = mint(userId, familyId = ids.opaque(), deviceInfo, now)

    fun accessTokenFor(userId: UserId): IssuedAccessToken = accessTokens.issue(userId.value)

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
                deviceInfo = previous.deviceInfo,
            )
        return token to IssuedRefreshToken(secret.value, token.expiresAt)
    }

    private fun mint(
        userId: UserId,
        familyId: UUID,
        deviceInfo: String?,
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
                deviceInfo = deviceInfo,
            )
        tokens.insert(token)
        return IssuedRefreshToken(secret.value, token.expiresAt)
    }
}
