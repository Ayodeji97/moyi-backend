package com.moyi.identity.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.identity.api.AccountDeletions
import com.moyi.identity.domain.UserId
import com.moyi.identity.infra.database.AccountErasure
import com.moyi.identity.infra.database.DeletionRequestStore
import com.moyi.notification.api.PushTokens
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * The [AccountDeletions] port: identity's half of carrying a deletion out (ADR-0058 decision 6).
 *
 * **One transaction per account**, holding that user's row lock: the request is read again
 * under it (a cancel that committed while this waited wins, and one that commits after finds
 * the request carried out), the account is anonymised, its push tokens forgotten, the request
 * marked carried out, and `AccountDeleted` published, all in one commit. Every other module's
 * share is done by its consumer of that event (`bond`, `gratitude`, `media`, `support`), each
 * retried by the outbox until it succeeds and idempotent when it is retried, so a crash at any
 * point leaves either nothing done or a commit the consumers will finish.
 *
 * Nothing about the person is logged: an id, and that it happened.
 */
@Service
internal class ExecuteAccountDeletions(
    private val requests: DeletionRequestStore,
    private val erasure: AccountErasure,
    private val pushTokens: PushTokens,
    private val outbox: EventPublisher,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) : AccountDeletions {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun executeDue(limit: Int): Int {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return requests.dueBy(now, limit).count { userId ->
            // One account that cannot be carried out must not hold up the rest: it is said, by
            // its id and the exception's class (never its message, which may carry a row), and
            // taken again by the next run, its transaction having committed nothing.
            try {
                transactions.execute { executeOne(userId) } == true
            } catch (failed: DataAccessException) {
                log.warn("Account deletion for user {} failed and will be retried: {}", userId.value, failed.javaClass.simpleName)
                false
            }
        }
    }

    private fun executeOne(userId: UserId): Boolean {
        requests.lockUser(userId)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        // Read again under the lock: a request cancelled meanwhile is no longer pending. Once due it
        // cannot be cancelled and no second one can be made, so the one found is the one that fell due.
        val request = requests.findPending(userId) ?: return false
        erasure.erase(userId, now)
        pushTokens.forgetAllOf(userId.value)
        requests.markExecuted(request, now)
        val id = userId.value
        outbox.publish(OutboxEvent("User", id, AccountDeletions.EVENT, mapOf(AccountDeletions.USER_ID to id), now))
        log.info("Account deletion carried out for user {}", id)
        return true
    }
}
