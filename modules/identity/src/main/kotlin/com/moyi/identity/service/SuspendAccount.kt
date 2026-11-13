package com.moyi.identity.service

import com.moyi.common.core.IdGenerator
import com.moyi.identity.api.AccountSuspension
import com.moyi.identity.api.SuspensionOutcome
import com.moyi.identity.api.SuspensionResult
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.UserStatus
import com.moyi.identity.infra.database.AccountSuspensionStore
import com.moyi.identity.infra.database.DeletionRequestStore
import com.moyi.identity.infra.database.RefreshTokenStore
import com.moyi.identity.infra.database.Standing
import com.moyi.notification.api.PushTokens
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The [AccountSuspension] port (admin console A3, spec rulings 4–6 and 15).
 *
 * **Locks, in the order every identity path takes them** (spec §8, ADR-0058 decision 6): the
 * sessions advisory lock, then the user's row; then refresh tokens, push tokens and the span.
 * No bond, day or entry lock: `bond` reads spans without one, so nothing here can wait on a
 * bond and nothing holding a bond waits here. The act's instant is read from the clock **after**
 * both locks are held: a suspension that waited for a sign-in or the deletion job is stamped
 * when it took effect, not when it was asked for.
 *
 * Erasure is not changed by any of this (plan ruling 2): a pending deletion is carried out on
 * time, suspended or not, and the account's open span outlives it.
 *
 * Only ids reach the log.
 */
@Service
@Suppress("LongParameterList") // The locks' two stores, the span's, the push tokens, ids and the clock: each named.
internal class SuspendAccount(
    private val sessions: RefreshTokenStore,
    private val suspensions: AccountSuspensionStore,
    private val requests: DeletionRequestStore,
    private val pushTokens: PushTokens,
    private val ids: IdGenerator,
    private val clock: Clock,
) : AccountSuspension {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun suspend(userId: UUID): SuspensionResult {
        val id = UserId(userId)
        val standing = lock(id) ?: return NO_SUCH_ACCOUNT
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val outcome =
            when (standing.status) {
                UserStatus.ACTIVE, UserStatus.PENDING_VERIFICATION -> {
                    // A token's `iat` is whole seconds (ADR-0019), as erasure reasons: set to `now`,
                    // one issued earlier in the same second would live out its fifteen minutes.
                    suspensions.markSuspended(id, now, now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1))
                    sessions.revokeAllForUser(id, now)
                    pushTokens.forgetAllOf(userId)
                    suspensions.openSpan(ids.timeOrdered(), id, now)
                    log.info("Account suspended for user {}", userId)
                    SuspensionOutcome.SUSPENDED
                }

                UserStatus.SUSPENDED -> {
                    SuspensionOutcome.ALREADY_SUSPENDED
                }

                UserStatus.PENDING_DELETION, UserStatus.DELETED -> {
                    SuspensionOutcome.GONE
                }
            }
        return result(id, outcome, standing)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lift(userId: UUID): SuspensionResult {
        val id = UserId(userId)
        val standing = lock(id) ?: return NO_SUCH_ACCOUNT
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val outcome =
            when (standing.status) {
                UserStatus.SUSPENDED -> {
                    // Ruling 5: the status the account has earned, not a stored one. A verification
                    // recorded while suspended counts (`User.verifyEmail` keeps the status).
                    val earned = if (standing.emailVerified) UserStatus.ACTIVE else UserStatus.PENDING_VERIFICATION
                    suspensions.markLifted(id, earned, now)
                    val closed = suspensions.closeOpenSpan(id, now)
                    check(closed == 1) { "a suspended account had $closed open suspension spans, not one" }
                    log.info("Account suspension lifted for user {}", userId)
                    SuspensionOutcome.LIFTED
                }

                UserStatus.ACTIVE, UserStatus.PENDING_VERIFICATION -> {
                    SuspensionOutcome.NOT_SUSPENDED
                }

                UserStatus.PENDING_DELETION, UserStatus.DELETED -> {
                    SuspensionOutcome.GONE
                }
            }
        return result(id, outcome, standing)
    }

    /** The sessions lock, then the row: the order sign-in, refresh, reset and the deletion job take. */
    private fun lock(id: UserId): Standing? {
        sessions.lockSessionsOf(id)
        return suspensions.lockStanding(id)
    }

    /** Read under the same locks, so a cancel or a run of the job cannot slip between. */
    private fun result(
        id: UserId,
        outcome: SuspensionOutcome,
        standing: Standing,
    ): SuspensionResult {
        val due: Instant? = if (outcome == SuspensionOutcome.GONE) null else requests.findPending(id)?.cancellableUntil
        return SuspensionResult(outcome, standing.status.toAccountStatus(), due)
    }

    private companion object {
        val NO_SUCH_ACCOUNT = SuspensionResult(SuspensionOutcome.NO_SUCH_ACCOUNT, statusBefore = null, deletionDueAt = null)
    }
}
