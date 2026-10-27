package com.moyi.notification.api

import java.time.Instant
import java.util.UUID

/**
 * How a module says that a member is owed a push (ADR-0044, spec §2.2).
 *
 * **The caller decides who is told what; this module only carries it.**
 * `notification` is a leaf and can read no member, bond or day (spec §2.1), so
 * a [PushRequest] arrives with everything already resolved: whose push it is,
 * which Bond, which category, and the key that makes it one push and not two.
 *
 * **Recorded, not sent.** [request] writes the push to the ledger and publishes
 * `PushRequested` to the outbox, both in the caller's transaction, and returns.
 * The `notification.push` consumer sends it later, in a transaction of its own.
 * A caller's transaction never waits on a push provider, and a request whose
 * transaction rolls back was never made.
 */
interface Notifications {
    /**
     * Records [request], once. **Joins the caller's transaction and refuses to
     * run without one**: the request and the outbox event are written together
     * or not at all, and the outbox refuses a publication at any isolation
     * stricter than READ COMMITTED (ADR-0035).
     *
     * @return `true` if this was the first request with its dedupe key, `false`
     *   if one already existed. A second request is not an error: it is the same
     *   event delivered twice to the consumer that asked (ADR-0035 decision 6),
     *   and it is dropped here.
     */
    fun request(request: PushRequest): Boolean
}

/**
 * One push to one member.
 *
 * @property dedupeKey Names the push, not the request: the same fact asked
 *   about twice must give the same key, e.g. `reveal:{bondDayId}:{userId}`
 *   (spec N3). At most 200 characters.
 * @property staleAfter When the push stops being worth sending. A push that
 *   is still unsent then is recorded `EXPIRED` and never sent: the outbox
 *   retries for ever, and a reveal told the next morning is worse than none
 *   (spec §2.2 point 2).
 */
data class PushRequest(
    val userId: UUID,
    val bondId: UUID,
    val category: PushCategory,
    val dedupeKey: String,
    val staleAfter: Instant,
) {
    init {
        require(dedupeKey.length in 1..MAX_DEDUPE_KEY_LENGTH) { "a dedupe key must be 1 to $MAX_DEDUPE_KEY_LENGTH characters" }
    }

    companion object {
        /** The CHECK in V29. */
        const val MAX_DEDUPE_KEY_LENGTH = 200
    }
}

/**
 * What a push is about (FR-080, and the owner's rulings Q8 and Q13). It
 * chooses the localisation keys a phone shows and nothing else: no push
 * carries words written on the server, a name or a Bond's name (spec §5 Q6).
 */
enum class PushCategory {
    REMINDER,
    PARTNER_JOINED,
    REVEAL,

    /** A day that opened with one entry (Q8): worded as an opening, never as a miss (FR-076, T-09). */
    SOLO_UNLOCK,
    MILESTONE,
    ON_THIS_DAY,

    /** A two-party proposal waiting on the other member (Q13). */
    PROPOSAL_OPENED,
}
