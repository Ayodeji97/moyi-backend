package com.moyi.notification.api

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * How a module says that a member is owed a push (ADR-0044, spec §2.2).
 *
 * **The caller decides who is told what; this module only carries it.**
 * `notification` is a leaf and can read no member, bond or day (spec §2.1), so
 * a [PushRequest] arrives with everything already resolved: whose push it is,
 * which Bond, which category, the key that makes it one push and not two, and
 * the member's own settings as they stood when it was asked for
 * ([PushRecipient], ADR-0045).
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

    /**
     * Calls off the push recorded under [dedupeKey], if it has not gone yet:
     * one still `REQUESTED`, or `HELD` by quiet hours, becomes `CANCELLED` and
     * is never sent. A push already settled, or never asked for, is left as it
     * is. For a push whose reason has gone before it could be sent: the daily
     * reminder of a member who has since written (ADR-0045).
     *
     * **Joins the caller's transaction and refuses to run without one**, so
     * the push is called off exactly when the fact that made it pointless
     * commits. Calling it twice is calling it once.
     *
     * @return whether this call cancelled a push.
     */
    fun cancel(dedupeKey: String): Boolean
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
 * @property recipient The member's settings in this Bond, resolved by the
 *   caller: this module cannot read them (spec §2.1). Required, with no
 *   default, so that no caller can forget a member's mute or quiet hours.
 */
data class PushRequest(
    val userId: UUID,
    val bondId: UUID,
    val category: PushCategory,
    val dedupeKey: String,
    val staleAfter: Instant,
    val recipient: PushRecipient,
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
    ;

    /**
     * Reveal-class (FR-082): may take the one slot per Bond per day that
     * nothing else can spend. A day opening with one entry is the same news
     * as a day opening with two, at the same moment, so it is the same class.
     */
    val isRevealClass: Boolean get() = this == REVEAL || this == SOLO_UNLOCK
}

/**
 * One member's notification settings in one Bond, as they stood when a push
 * was asked for (FR-081, ADR-0045). The caller reads them from wherever it
 * keeps them (`bond_members`) and hands over values.
 *
 * @property zone The member's reminder zone (`bond_members.reminder_timezone`):
 *   the zone their quiet hours are in, and the zone whose day FR-082's
 *   per-Bond budget counts (spec D-16).
 * @property quietHours `null` when the member has none: off by default (Q7).
 */
data class PushRecipient(
    val zone: ZoneId,
    val quietHours: QuietHoursWindow?,
    val preferences: NotificationPreferences,
)

/**
 * A member's quiet hours, `[start, end)` in their zone's local time. A window
 * that wraps midnight (22:00 to 07:00) is the ordinary case. A window whose
 * start equals its end is empty: it holds nothing back (ADR-0045).
 */
data class QuietHoursWindow(
    val start: LocalTime,
    val end: LocalTime,
)

/**
 * Which kinds of push a member wants in one Bond (FR-081, spec §4 N2). The
 * one place that says which toggle governs which [PushCategory], and what a
 * member who never chose has.
 *
 * **Defaults (Q7): everything on except on-this-day**, which FR-092 makes
 * opt-in. [proposal] is a sixth toggle beyond the spec's five: Q13 rules the
 * pending-proposal push in, and FR-081 gives a member control of *which
 * categories they receive*, so a push with no toggle would be the one
 * category a member could not turn off (ADR-0045).
 */
data class NotificationPreferences(
    val reminder: Boolean = true,
    val partnerJoined: Boolean = true,
    val reveal: Boolean = true,
    val milestone: Boolean = true,
    val onThisDay: Boolean = false,
    val proposal: Boolean = true,
) {
    /** Whether this member wants a push of [category]. A day opening with one entry is governed by [reveal]. */
    fun allows(category: PushCategory): Boolean =
        when (category) {
            PushCategory.REMINDER -> reminder
            PushCategory.PARTNER_JOINED -> partnerJoined
            PushCategory.REVEAL, PushCategory.SOLO_UNLOCK -> reveal
            PushCategory.MILESTONE -> milestone
            PushCategory.ON_THIS_DAY -> onThisDay
            PushCategory.PROPOSAL_OPENED -> proposal
        }

    companion object {
        /** What a member who never chose has: on, except on-this-day (Q7, FR-092). */
        val DEFAULTS = NotificationPreferences()
    }
}
