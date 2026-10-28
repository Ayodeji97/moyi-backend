package com.moyi.bond.api

import com.moyi.notification.api.PushRecipient
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * Who in a Bond is told of something that happened to it, and how each of
 * them wants to be told (spec §4 N3, ADR-0046): what the push consumers in
 * `gratitude` and `bond` ask of `bond`, which keeps every member's zone,
 * quiet hours and toggles.
 *
 * **Unguarded, like [ReminderRoster] and the closer's view.** It answers an
 * event or a timer, never a request, and it names members of any bond.
 * `ArchitectureTest` holds that only the consumers and the sweeps that push
 * may call it.
 *
 * **The decision is the Bond's, and is made once** (ADR-0041 H-M5, the
 * owner's Q9): [audienceOf] answers whether the Bond takes writes and who its
 * members are, both together. A member's own toggle, quiet hours or budget
 * hold back only their own copy, and are applied by `notification` from the
 * [PushRecipient] each member carries here, never by whoever asks.
 */
interface PushAudiences {
    /**
     * The Bond's members as they stand now, with whether it takes writes;
     * `null` when there is no such bond. A Bond takes writes while it is
     * waiting for its second member or active; ended, left, blocked or
     * counting down to deletion, it does not, and nothing is pushed about it
     * (doc 02:144, ADR-0041 Owed, ADR-0040:141).
     */
    fun audienceOf(bondId: UUID): PushAudience?

    /**
     * Every member who has opted in to the on-this-day push (FR-092), in a
     * Bond that takes writes, who has not left it, whose [localTime] in their
     * reminder zone fell within `(after, upTo]`. Each with the bond-day it fell
     * in, by the Bond's own calendar, and their settings resolved.
     *
     * Whether they have anything to remember is not asked here: the days and
     * entries are `gratitude`'s.
     */
    fun onThisDayDueBetween(
        localTime: LocalTime,
        after: Instant,
        upTo: Instant,
    ): List<DueReminder>
}

/**
 * A Bond as a push sees it. `internal constructor`, as [BondMembership]'s: a
 * caller outside `bond` may read one and cannot make one.
 *
 * @property takesWrites the Bond-level half of every push decision: `false`
 *   and nobody is told anything.
 * @property members the members who have not left, in a stable order.
 */
class PushAudience internal constructor(
    val bondId: UUID,
    val takesWrites: Boolean,
    val members: List<PushAudienceMember>,
) {
    /** Ids only. */
    override fun toString(): String = "PushAudience(bondId=$bondId, takesWrites=$takesWrites, members=${members.size})"
}

/**
 * One member of a [PushAudience]: who they are, and their settings in this
 * Bond as they stand now.
 */
class PushAudienceMember internal constructor(
    val memberId: UUID,
    val userId: UUID,
    val recipient: PushRecipient,
) {
    /** Ids only: when somebody sleeps, and what they mute, are theirs (doc 18 §5). */
    override fun toString(): String = "PushAudienceMember(memberId=$memberId)"
}
