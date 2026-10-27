package com.moyi.notification.domain

import java.time.Instant

/** A row of the ledger's `status` column (V29). One CHECK, one enum. */
internal enum class NotificationStatus {
    /** Recorded, and owed to the `notification.push` consumer. Not final. */
    REQUESTED,

    /**
     * Arrived inside the member's quiet hours and waits for their end, at
     * `not_before` (Q7, ADR-0045). Not final: released back to `REQUESTED`
     * by [com.moyi.notification.api.HeldPushes].
     */
    HELD,
    SENT,

    /** The member has the category turned off (FR-081). */
    SUPPRESSED,

    /** Over FR-082's budget: dropped, never queued. */
    DROPPED_BUDGET,

    /** Still unsent at `stale_after`: never sent late. */
    EXPIRED,

    /** Nothing more can be done: no phone, no valid token, or a refusal that would be repeated. */
    FAILED_PERMANENT,
}

/** What one send to one phone came to, as the settlement needs it. The port's richer answer is mapped onto this. */
internal enum class SendResult {
    ACCEPTED,
    TOKEN_INVALID,
    REJECTED,
    UNAVAILABLE,
}

/** What the consumer does with a push once every phone has been tried. */
internal sealed interface Settlement {
    data object Sent : Settlement

    /** Throw, roll back, and let the outbox back off and try the whole push again (FR-086). */
    data object Retry : Settlement

    data class Failed(
        val errorClass: String,
    ) : Settlement
}

/**
 * The two rules of the push consumer that need nothing but values (spec §2.2,
 * ADR-0044). Everything around them — the row, the tokens, the provider — is
 * the service's.
 */
internal object PushSettlement {
    /** A provider refused the message itself. */
    const val REJECTED = "REJECTED"

    /** Every token the member had was refused as invalid, and has been forgotten. */
    const val NO_VALID_TOKEN = "NO_VALID_TOKEN"

    /** The member has no phone with a token. */
    const val NO_DEVICE = "NO_DEVICE"

    /**
     * Expired from [staleAfter] on. The outbox retries for ever (ADR-0035
     * decision 5), so without its own deadline a push held up by a provider
     * outage would arrive hours after its day; this is that deadline.
     */
    fun isExpired(
        staleAfter: Instant,
        now: Instant,
    ): Boolean = !now.isBefore(staleAfter)

    /**
     * **Sent if any phone took it.** A retry would send it again to that
     * phone to reach another; a push is a hint and the app is correct without
     * it (doc 25:69), so the phone that missed it is let go.
     *
     * **Otherwise retried if any phone may yet take it.** Then refused, then
     * out of tokens, then nobody to send to: each is final, and the word
     * recorded says which.
     */
    fun settle(results: List<SendResult>): Settlement =
        when {
            SendResult.ACCEPTED in results -> Settlement.Sent
            SendResult.UNAVAILABLE in results -> Settlement.Retry
            SendResult.REJECTED in results -> Settlement.Failed(REJECTED)
            results.isNotEmpty() -> Settlement.Failed(NO_VALID_TOKEN)
            else -> Settlement.Failed(NO_DEVICE)
        }
}
