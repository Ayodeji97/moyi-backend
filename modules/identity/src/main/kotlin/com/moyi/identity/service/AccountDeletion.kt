package com.moyi.identity.service

import com.moyi.common.core.IdGenerator
import com.moyi.common.web.ApiException
import com.moyi.common.web.ErrorCode
import com.moyi.common.web.NotFoundException
import com.moyi.identity.domain.DeletionRequest
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.PasswordHasher
import com.moyi.identity.domain.SecurityNotice
import com.moyi.identity.domain.UserId
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.DeletionRequestStore
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * `POST` and `DELETE /me/deletion-request` (FR-008, doc 06 §3.2, ADR-0058), and the pending
 * request `GET /me` shows.
 *
 * **The password is asked for again** (ADR-0058 decision 1): a session is not proof enough to
 * start the one thing that cannot be undone, and an unlocked phone in someone else's hand is
 * the case doc 09 names. It is verified outside every transaction, as login's is, and a wrong
 * one is not counted towards the sign-in lock: a thief holding a session could otherwise lock
 * the owner out of signing in to cancel. The per-user bucket bounds the guesses instead.
 *
 * **Nothing else changes at request time** (decision 2). The status stays what it was, the
 * person can still sign in, and their other sessions go on: a thief with the password who
 * could also sign the owner out everywhere would leave them nothing to cancel with. The email
 * is how the owner learns of it.
 */
@Service
@Suppress("LongParameterList") // The stores, the hasher, the ids, the clock, the transaction and the notice: each named.
internal class AccountDeletion(
    private val accounts: AccountStore,
    private val requests: DeletionRequestStore,
    private val passwords: PasswordHasher,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The pending request, made now or already waiting: the same request, with the same dates,
     * however often it is asked for (the idempotent `202`).
     *
     * @throws DeletionPasswordRefusedException the password is not the account's
     */
    fun request(
        userId: UserId,
        password: String,
    ): DeletionRequest {
        val credentials = accounts.findCredentials(userId) ?: throw AuthenticatedUserMissingException()
        if (!passwords.matches(Password.normalised(password), credentials.passwordHash)) {
            log.info("Account deletion request refused for user {}: password", userId.value)
            throw DeletionPasswordRefusedException()
        }
        return checkNotNull(
            transactions.execute {
                requests.lockUser(userId)
                requests.findPending(userId) ?: open(userId)
            },
        )
    }

    /** Under the user's lock, so two requests at once are one row (and V52's index refuses a second regardless). */
    private fun open(userId: UserId): DeletionRequest {
        val user = accounts.findById(userId) ?: throw AuthenticatedUserMissingException()
        val request = DeletionRequest.open(ids.timeOrdered(), userId, clock.instant())
        requests.insert(request)
        log.info("Account deletion requested for user {}, due {}", userId.value, request.cancellableUntil)
        // Only to a confirmed address (ADR-0057 decision 6): an unconfirmed one may be somebody else's.
        if (user.isEmailVerified) {
            events.publishEvent(
                SecurityNotice.DeletionRequested(user.id, user.email, user.displayName, request.id, request.cancellableUntil),
            )
        }
        return request
    }

    /**
     * Calls the pending request off. Nothing pending, and a window that has closed, are one
     * answer ([NoCancellableDeletionException]): the person can do nothing about either.
     */
    @Transactional
    fun cancel(userId: UserId) {
        requests.lockUser(userId)
        val now = clock.instant()
        val pending = requests.findPending(userId)?.takeIf { it.canBeCancelledAt(now) } ?: throw NoCancellableDeletionException()
        requests.cancel(pending, now)
        log.info("Account deletion cancelled for user {}", userId.value)
    }

    @Transactional(readOnly = true)
    fun pendingOf(userId: UserId): DeletionRequest? = requests.findPending(userId)
}

/**
 * `403 INVALID_CREDENTIALS`, without `WWW-Authenticate`: the session is fine, the password typed is
 * not (ADR-0058 decision 1, amended 2026-11-10, #166). Not `401`: on a signed-in route a client reads
 * `401` as its access token refused, and refreshes and retries. A signed-in route that re-checks the
 * password answers a wrong one `403` with this code; `401` stays with the refused token (with its
 * challenge) and with signed-out login (without one, ADR-0020 decision 1).
 */
internal class DeletionPasswordRefusedException :
    ApiException(HttpStatus.FORBIDDEN, ErrorCode.INVALID_CREDENTIALS, "That password is not the one for this account.")

/** One `404` for "nothing pending" and "too late to cancel" (ADR-0058 decision 3). */
internal class NoCancellableDeletionException : NotFoundException("There is no account deletion that can still be cancelled.")
