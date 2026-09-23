package com.moyi.common.security

import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The one question the verifier asks the database: has this user's past
 * been invalidated, and since when?
 *
 * A port, defined here and implemented by `identity`, because the filter
 * chain lives in `common:security` and cannot depend on a module — the
 * dependency runs the other way. Doc 09 §3: revocation is
 * `users.tokens_invalid_before`, bumped by `logout-all`, password reset and
 * admin suspension, so that revoking "all sessions" genuinely includes the
 * access tokens still in flight rather than leaving them valid for fifteen
 * minutes. A denylist of `jti`s would need every issued token remembered;
 * one timestamp per user needs nothing remembered at all.
 *
 * **One Postgres read per authenticated request, for now.** Doc 07 §5 caches
 * this in Redis for fifteen minutes; that arrives with the Redis slice, and
 * the cache must sit behind this same port so Postgres stays the source of
 * truth (doc 25 D6: revocation must not fail open on a flush).
 */
fun interface TokenRevocations {
    /** `null` when there is no such user — which is itself a reason to reject the token. */
    fun forUser(userId: UUID): TokenRevocation?
}

/** What is known about a user's revocation state. [invalidBefore] `null` means nothing has been revoked. */
data class TokenRevocation(
    val invalidBefore: Instant?,
)

/**
 * Rejects a token issued before the user's `tokens_invalid_before`, or for a
 * user who no longer exists.
 *
 * **Compared at one-second resolution**, because that is the resolution
 * `iat` has: a JWT's timestamps are whole seconds, so a token issued at
 * 12:00:00.900 says `12:00:00`. Compared against a millisecond bump at
 * 12:00:00.500 it would read as *earlier* and be rejected — including a token
 * issued by the very login that followed `logout-all` in the same second.
 * Truncating both sides makes the rule "a token from a second before the
 * bump is dead", which is what the document means and one second wider than
 * the naive comparison. The caller bumping the column does not need to know.
 */
class RevocationValidator(
    private val revocations: TokenRevocations,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val reason = reasonToRefuse(token)
        return if (reason == null) OAuth2TokenValidatorResult.success() else failure(reason)
    }

    /** `null` means the token passes this validator. One expression, so every branch is visible at once. */
    private fun reasonToRefuse(token: Jwt): String? {
        val userId = token.subject?.let(::parseUuidOrNull)
        val issuedAt = token.issuedAt
        return when {
            userId == null -> "The token's subject is not a user id"
            issuedAt == null -> "The token has no issued-at claim"
            else -> reasonToRefuse(revocations.forUser(userId), issuedAt)
        }
    }

    private fun reasonToRefuse(
        revocation: TokenRevocation?,
        issuedAt: Instant,
    ): String? {
        val invalidBefore = revocation?.invalidBefore
        return when {
            revocation == null -> {
                "The token's subject no longer exists"
            }

            invalidBefore == null -> {
                null
            }

            issuedAt.truncatedTo(ChronoUnit.SECONDS) < invalidBefore.truncatedTo(ChronoUnit.SECONDS) -> {
                "The token was issued before the user's sessions were revoked"
            }

            else -> {
                null
            }
        }
    }

    private fun failure(description: String): OAuth2TokenValidatorResult =
        // `invalid_token`, the RFC 6750 code the entry point turns into the
        // WWW-Authenticate error. The description reaches the log, not the client.
        OAuth2TokenValidatorResult.failure(OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null))

    @Suppress("SwallowedException")
    private fun parseUuidOrNull(value: String): UUID? =
        try {
            UUID.fromString(value)
        } catch (notAUuid: IllegalArgumentException) {
            null
        }
}
