package com.moyi.identity.service

import com.moyi.identity.domain.RefreshToken
import com.moyi.identity.domain.RefreshTokenInvalidException
import com.moyi.identity.domain.RefreshTokenReusedException
import com.moyi.identity.domain.SecurityNotice
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.RefreshTokenStore
import com.moyi.identity.infra.security.RefreshProperties
import com.moyi.notification.api.PushTokens
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
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
 * Four outcomes, in the order they are checked:
 *
 * 1. **Invalid.** Unknown, expired, revoked, or in a family already revoked.
 *    `401 REFRESH_TOKEN_INVALID`; the client signs in again. A stale token
 *    presented after its family is gone is not a second theft: no email.
 * 2. **Grace retry** (ADR-0021, amended 2026-11-08, issue #136). The token was
 *    rotated less than the grace window ago, by being used, and its successor
 *    has not been used: the answer to that rotation was most likely lost on
 *    the way back. It is rotated again — a new pair, the unused successor
 *    voided, nobody emailed. The window runs from the first rotation.
 * 3. **Reuse.** Rotated, and not a grace retry: outside the window, or its
 *    successor already used, or itself a voided successor. Somebody is
 *    holding a stale copy — the thief, or the victim after the thief — and
 *    the system cannot tell which. The whole family is revoked, the person is
 *    emailed ([SecurityNotice.SessionReuseDetected]), a WARN names the user
 *    and family, and the client gets `401 TOKEN_REUSE_DETECTED` (doc 13).
 * 4. **Live.** A conditional `UPDATE` marks it rotated — the same compare-and-
 *    set shape as verification consumption — and the successor is inserted in
 *    the same transaction.
 *
 * Every path first takes the per-user sessions lock
 * (`RefreshTokenStore.lockSessionsOf`), then reads the presented token, and
 * its successor, fresh and row-locked (`SELECT … FOR UPDATE`). The compare-
 * and-set alone is not enough: a family revocation is a single `UPDATE` over
 * the rows in its snapshot, and a rotation committing concurrently can insert
 * a successor that snapshot never saw — a "revoked" family with one live
 * token in it (the Codex review of the first version). Two presentations of
 * one token at the same instant therefore run one after the other: the first
 * rotates it, the second is a grace retry that voids the first's successor,
 * and the family never holds two live tokens.
 *
 * The access token is minted after the commit: a signed token for a rotation
 * that rolled back would be a valid credential for nothing that happened.
 */
@Service
@Suppress("LongParameterList") // What a rotation touches, each named: the push tokens since ADR-0044.
internal class RotateRefreshToken(
    private val tokens: RefreshTokenStore,
    private val accounts: AccountStore,
    private val sessions: IssueSessionTokens,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
    private val events: ApplicationEventPublisher,
    private val pushTokens: PushTokens,
    private val properties: RefreshProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws RefreshTokenReusedException the token was already rotated; its family is now revoked
     * @throws RefreshTokenInvalidException unknown, expired, revoked, or its user can no longer sign in
     */
    fun rotate(presented: String): RotatedTokens {
        // Microseconds, the resolution Postgres keeps: `rotated_at` is read back
        // and compared with a later `now` to measure the grace window.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val hash = runCatching { VerificationSecret(presented.trim()).hash() }.getOrNull()

        val outcome =
            transactions.execute {
                val found = hash?.let(tokens::findRefByHash) ?: return@execute Outcome.Invalid
                // Serialise against every other mutation of this user's
                // sessions, then read the row fresh and locked: it may have
                // been rotated or revoked by whoever held the lock while we
                // waited for it. (The lookup above loads no entity, so this
                // read is not answered from the persistence context.)
                tokens.lockSessionsOf(UserId(found.userId))
                val previous = tokens.lockById(found.id) ?: return@execute Outcome.Invalid
                when {
                    previous.revokedAt != null -> Outcome.Invalid
                    previous.rotatedAt != null -> retryOrReuse(previous, now)
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
    ): Outcome =
        // The compare-and-set. If it fails, a concurrent request rotated this
        // token a moment ago — a reuse by doc 09's definition. Under the locks
        // above it cannot; it is kept because it costs nothing and is what
        // made this correct before they existed.
        issueNext(previous, now) { successor -> tokens.rotate(previous.id, successor, now) }

    /**
     * The token was rotated already. Inside the grace window, with its
     * successor still unused, the caller most likely never received that
     * rotation's answer: rotate again and void the successor nobody holds.
     * Otherwise, a reuse.
     */
    private fun retryOrReuse(
        previous: RefreshToken,
        now: Instant,
    ): Outcome {
        val unused = previous.replacedBy?.let(tokens::lockById)
        if (!previous.admitsRetry(unused, now, properties.graceWindow)) return reuse(previous, now)
        checkNotNull(unused)
        return issueNext(previous, now) { successor ->
            tokens.void(unused.id, now) && tokens.repoint(previous.id, unused.id, successor)
        }.also { outcome ->
            if (outcome is Outcome.Rotated) {
                log.info(
                    "Refresh retried inside the grace window for user {}: family {} rotated again",
                    previous.userId.value,
                    previous.familyId,
                )
            }
        }
    }

    /**
     * Mints the next token of [previous]'s family and records it with [link],
     * which returns `false` if the state it expects has gone — then it is a
     * reuse. An account that can no longer sign in has its sessions end here.
     */
    private fun issueNext(
        previous: RefreshToken,
        now: Instant,
        link: (successorId: UUID) -> Boolean,
    ): Outcome {
        val user = accounts.findById(previous.userId)?.takeIf { it.canAuthenticate }
        val (successor, issued) = sessions.successor(previous, now)
        return when {
            user == null -> {
                // Not a reuse — nobody did anything wrong with the token.
                tokens.revokeFamily(previous.familyId, now)
                previous.deviceId?.let(pushTokens::forget)
                Outcome.Invalid
            }

            !link(successor.id) -> {
                reuse(previous, now)
            }

            else -> {
                tokens.insert(successor)
                // FR-007: a rotation is the device being seen again.
                previous.deviceId?.let { sessions.deviceSeen(it, now) }
                Outcome.Rotated(user, previous.familyId, issued)
            }
        }
    }

    private fun reuse(
        previous: RefreshToken,
        now: Instant,
    ): Outcome {
        val revoked = tokens.revokeFamily(previous.familyId, now)
        // The phone may be the thief's: it is told nothing more (ADR-0044).
        previous.deviceId?.let(pushTokens::forget)
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
