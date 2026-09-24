package com.moyi.identity.service

import com.moyi.identity.domain.RefreshToken
import com.moyi.identity.domain.RefreshTokenInvalidException
import com.moyi.identity.domain.RefreshTokenReusedException
import com.moyi.identity.domain.SecurityNotice
import com.moyi.identity.domain.User
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.RefreshTokenStore
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class RotatedTokens(
    val user: User,
    val tokens: SessionTokens,
)

/**
 * `POST /auth/refresh` (doc 06 §3.1, doc 09 §3): rotation with reuse detection
 * and family revocation — the mechanism that makes stateless auth safe, and
 * the one doc 25 D5 calls the most probed backend topic in interviews.
 *
 * Three outcomes, in the order they are checked:
 *
 * 1. **Reuse.** The presented token was already rotated. Somebody is holding a
 *    stale copy — the thief, or the victim after the thief — and the system
 *    cannot tell which. The whole family is revoked, the person is emailed
 *    ([SecurityNotice.SessionReuseDetected]), a WARN names the user and family,
 *    and the client gets `401 TOKEN_REUSE_DETECTED` so it wipes what it holds
 *    (doc 13). A token in a family that is *already* revoked is not a second
 *    reuse: it is refused as invalid, without a second email.
 * 2. **Invalid.** Unknown, expired, or revoked. `401 REFRESH_TOKEN_INVALID`;
 *    the client signs in again.
 * 3. **Live.** A conditional `UPDATE` marks it rotated — the same compare-and-
 *    set shape as verification consumption — and the successor is inserted in
 *    the same transaction. Two concurrent presentations of one live token:
 *    one wins the `UPDATE`, the other finds the row already rotated and is
 *    treated as a reuse, exactly as doc 09 specifies.
 *
 * Every path first takes the per-user sessions lock
 * (`RefreshTokenStore.lockSessionsOf`) and re-reads the token. The compare-
 * and-set alone is not enough: a family revocation is a single `UPDATE` over
 * the rows in its snapshot, and a rotation committing concurrently can insert
 * a successor that snapshot never saw — a "revoked" family with one live
 * token in it. Found by the Codex review of the first version. **Strict, no grace
 *    window**: two legitimate concurrent refreshes will trip this, which is why
 *    doc 13 gives the client a mutex so it never sends two. ADR-0021 records
 *    the grace-window alternative and why it was not taken.
 *
 * The access token is minted after the commit: a signed token for a rotation
 * that rolled back would be a valid credential for nothing that happened.
 */
@Service
internal class RotateRefreshToken(
    private val tokens: RefreshTokenStore,
    private val accounts: AccountStore,
    private val sessions: IssueSessionTokens,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws RefreshTokenReusedException the token was already rotated; its family is now revoked
     * @throws RefreshTokenInvalidException unknown, expired, revoked, or its user can no longer sign in
     */
    fun rotate(presented: String): RotatedTokens {
        val now = clock.instant()
        val hash = runCatching { VerificationSecret(presented.trim()).hash() }.getOrNull()

        val outcome =
            transactions.execute {
                val found = hash?.let(tokens::findByHash) ?: return@execute Outcome.Invalid
                // Serialise against every other mutation of this user's
                // sessions, then re-read: the row may have been rotated or
                // revoked by whoever held the lock while we waited for it.
                tokens.lockSessionsOf(found.userId)
                val previous = tokens.findById(found.id) ?: return@execute Outcome.Invalid
                when {
                    previous.revokedAt != null -> Outcome.Invalid
                    previous.rotatedAt != null -> reuse(previous, now)
                    !previous.isLive(now) -> Outcome.Invalid
                    else -> rotateLive(previous, now)
                }
            }!!

        return when (outcome) {
            is Outcome.Rotated -> {
                RotatedTokens(
                    user = outcome.user,
                    tokens = SessionTokens(sessions.accessTokenFor(outcome.user.id, outcome.familyId), outcome.refreshToken),
                )
            }

            Outcome.Reused -> {
                throw RefreshTokenReusedException()
            }

            Outcome.Invalid -> {
                throw RefreshTokenInvalidException()
            }
        }
    }

    private fun rotateLive(
        previous: RefreshToken,
        now: Instant,
    ): Outcome {
        val user = accounts.findById(previous.userId)?.takeIf { it.canAuthenticate }
        return when {
            user == null -> {
                // The account can no longer sign in, so its sessions end here.
                // Not a reuse — nobody did anything wrong with the token.
                tokens.revokeFamily(previous.familyId, now)
                Outcome.Invalid
            }

            else -> {
                val (successor, issued) = sessions.successor(previous, now)
                // The compare-and-set. If it fails, a concurrent request rotated
                // this token a moment ago — a reuse by doc 09's definition.
                if (tokens.rotate(previous.id, successor.id, now)) {
                    tokens.insert(successor)
                    // FR-007: a rotation is the device being seen again.
                    previous.deviceId?.let { sessions.deviceSeen(it, now) }
                    Outcome.Rotated(user, previous.familyId, issued)
                } else {
                    reuse(previous, now)
                }
            }
        }
    }

    private fun reuse(
        previous: RefreshToken,
        now: Instant,
    ): Outcome {
        val revoked = tokens.revokeFamily(previous.familyId, now)
        log.warn(
            "Refresh token REUSE detected for user {}: family {} revoked ({} token(s)). The account holder is being emailed.",
            previous.userId.value,
            previous.familyId,
            revoked,
        )
        accounts.findById(previous.userId)?.let { user ->
            events.publishEvent(SecurityNotice.SessionReuseDetected(user.id, user.email, user.displayName, previous.familyId))
        }
        return Outcome.Reused
    }

    private sealed interface Outcome {
        data class Rotated(
            val user: User,
            val familyId: UUID,
            val refreshToken: IssuedRefreshToken,
        ) : Outcome

        data object Reused : Outcome

        data object Invalid : Outcome
    }
}
