package com.moyi.identity.service

import com.moyi.common.core.IdGenerator
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.security.IssuedAccessToken
import com.moyi.identity.domain.RefreshToken
import com.moyi.identity.domain.RefreshTokenInvalidException
import com.moyi.identity.domain.TokenGenerator
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.UserStatus
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.RefreshTokenStore
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class IssuedRefreshToken(
    val secret: String,
    val expiresAt: Instant,
)

internal data class RotatedTokens(
    val user: User,
    val accessToken: IssuedAccessToken,
    val refreshToken: IssuedRefreshToken,
)

@Suppress("LongParameterList")
@Service
internal class RefreshTokens(
    private val tokens: RefreshTokenStore,
    private val accounts: AccountStore,
    private val ids: IdGenerator,
    private val secrets: TokenGenerator,
    private val accessTokens: AccessTokenIssuer,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {
    fun issue(
        userId: UserId,
        deviceInfo: String?,
    ): IssuedRefreshToken {
        val now = clock.instant()
        return transactions.execute {
            issueInTransaction(userId, deviceInfo, now)
        }!!
    }

    fun rotate(rawSecret: String): RotatedTokens {
        val now = clock.instant()
        val result =
            transactions.execute {
                rotateInTransaction(rawSecret, now)
            }!!
        if (result is RotationOutcome.Invalid) throw RefreshTokenInvalidException()
        val success = result as RotationOutcome.Success
        return RotatedTokens(
            user = success.user,
            accessToken = accessTokens.issue(success.user.id.value),
            refreshToken = success.refreshToken,
        )
    }

    private fun issueInTransaction(
        userId: UserId,
        deviceInfo: String?,
        now: Instant,
    ): IssuedRefreshToken {
        val secret = secrets.verificationSecret()
        val token =
            RefreshToken.issue(
                id = ids.timeOrdered(),
                userId = userId,
                familyId = ids.opaque(),
                secret = secret,
                now = now,
                deviceInfo = deviceInfo,
            )
        tokens.insert(token)
        return IssuedRefreshToken(secret.value, token.expiresAt)
    }

    private fun rotateInTransaction(
        rawSecret: String,
        now: Instant,
    ): RotationOutcome =
        refreshHash(rawSecret)
            ?.let(tokens::findByHash)
            ?.let { previous ->
                when {
                    previous.revokedAt != null || previous.rotatedAt != null -> invalidateFamily(previous.familyId, now)
                    !previous.isLive(now) -> RotationOutcome.Invalid
                    else -> rotateLive(previous, now)
                }
            }
            ?: RotationOutcome.Invalid

    private fun refreshHash(rawSecret: String) =
        runCatching {
            com.moyi.identity.domain
                .VerificationSecret(rawSecret.trim())
                .hash()
        }.getOrNull()

    private fun rotateLive(
        previous: RefreshToken,
        now: Instant,
    ): RotationOutcome {
        val user = accounts.findById(previous.userId)
        return when {
            user == null -> {
                invalidateFamily(previous.familyId, now)
            }

            user.status != UserStatus.ACTIVE -> {
                invalidateFamily(previous.familyId, now)
            }

            else -> {
                val replacementSecret = secrets.verificationSecret()
                val replacement =
                    RefreshToken.issue(
                        id = ids.timeOrdered(),
                        userId = previous.userId,
                        familyId = previous.familyId,
                        secret = replacementSecret,
                        now = now,
                        deviceInfo = previous.deviceInfo,
                    )
                if (!tokens.rotate(previous.id, replacement.id, now)) {
                    invalidateFamily(previous.familyId, now)
                } else {
                    tokens.insert(replacement)
                    RotationOutcome.Success(user, IssuedRefreshToken(replacementSecret.value, replacement.expiresAt))
                }
            }
        }
    }

    private fun invalidateFamily(
        familyId: UUID,
        now: Instant,
    ): RotationOutcome {
        tokens.revokeFamily(familyId, now)
        return RotationOutcome.Invalid
    }

    private sealed interface RotationOutcome {
        data object Invalid : RotationOutcome

        data class Success(
            val user: User,
            val refreshToken: IssuedRefreshToken,
        ) : RotationOutcome
    }
}
