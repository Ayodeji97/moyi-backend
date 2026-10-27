package com.moyi.notification.api

import java.util.UUID

/**
 * The one way anything in this system sends a push (doc 25:118, ADR-0044).
 *
 * The same shape as [EmailSender] (ADR-0017): our type, the vendor (FCM) in
 * one `internal` class behind it, and a logger for local development that has
 * to be asked for by name. Sending is synchronous and reports, it does not
 * throw: what a failure means is the caller's decision, and the caller here is
 * the `notification.push` consumer, which turns each answer into a status in
 * the ledger, a pruned token or a retry.
 */
interface PushSender {
    fun send(message: PushMessage): PushDelivery
}

/**
 * One push to one phone. No text: the phone shows the localisation keys its
 * [category] names, in its own language (spec §5 Q6), and the app opens at
 * [bondId]. Neither a name nor anything anybody wrote is here, so nothing of
 * the kind can reach a lock screen or the provider (FR-084, T-14).
 *
 * [token] redacts itself; this class's [toString] is the generated one and is
 * safe for that reason alone.
 */
data class PushMessage(
    val token: PushToken,
    val category: PushCategory,
    val bondId: UUID,
)

/**
 * What became of one send. Four outcomes, because the consumer does four
 * different things with them.
 */
sealed interface PushDelivery {
    /** The provider took the message. [providerMessageId] is what to search its console for. */
    data class Accepted(
        val providerMessageId: String?,
    ) : PushDelivery

    /**
     * The token is no good and never will be: the app was uninstalled, or the
     * token is not one of this project's. The consumer forgets it (FR-083).
     */
    data class TokenInvalid(
        val reason: String,
    ) : PushDelivery

    /**
     * The provider refused this message and will refuse it again, for a
     * reason that is not the token: a payload it will not carry, credentials
     * it will not accept. Retrying is pointless.
     */
    data class Rejected(
        val reason: String,
    ) : PushDelivery

    /** The provider could not take it now: a timeout, a 5xx, a 429. Worth retrying as it is. */
    data class Unavailable(
        val reason: String,
    ) : PushDelivery
}
