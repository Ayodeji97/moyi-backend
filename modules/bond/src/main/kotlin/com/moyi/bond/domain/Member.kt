package com.moyi.bond.domain

import java.time.Instant
import java.time.LocalTime

/**
 * Doc 04 §3. `OWNER` is the member who created the bond; it carries no extra
 * authority in v1 — leave, block and every settings change are either
 * unilateral or mutual, never the owner's alone — and exists because the row
 * has to say who started the thing.
 */
internal enum class MemberRole { OWNER, MEMBER }

/**
 * One person's place in a bond (doc 04 §3).
 *
 * `reminderTimezone` is the **member's own** zone and is deliberately
 * independent of the bond's anchor: the bond decides which day an entry
 * belongs to, the member decides when their phone buzzes. Doc 04 §6 calls
 * conflating these the most likely source of a subtle production bug.
 *
 * `nicknameForOther`, not `nicknameForPartner` — ADR-0003's amendment took
 * the word "partner" out of the domain when `Space` became `Bond`, because a
 * bond generalises past couples. Doc 07 §2 still writes `nickname_for_partner`
 * and is amended by ADR-0026.
 *
 * A `data class`, and a member who leaves is `copy(leftAt = …)` rather than a
 * deleted row: each member keeps read access to the archive afterwards
 * (`states.md` §9).
 */
internal data class Member(
    val id: MemberId,
    val bondId: BondId,
    val userId: UserId,
    val role: MemberRole,
    val joinedAt: Instant,
    val leftAt: Instant?,
    val reminderTimeLocal: LocalTime,
    val reminderTimezone: RegionZone,
    val quietHoursStart: LocalTime?,
    val quietHoursEnd: LocalTime?,
    val nicknameForOther: String?,
) {
    init {
        // Mirrors V9's CHECK. Both exist on purpose: the database constraint
        // is the one that cannot be bypassed, this one is the one that fails
        // in a layer that can explain itself.
        require(nicknameForOther == null || nicknameForOther.length in 1..MAX_NICKNAME_LENGTH) {
            "a nickname must be between 1 and $MAX_NICKNAME_LENGTH characters"
        }
        // A start with no end is not a window: Phase 4's scheduler would have
        // to invent the other half, and V9 permits either column alone — so
        // this is the layer that says no. A window that wraps midnight
        // (22:00 → 07:00) is ordinary and stays legal.
        require((quietHoursStart == null) == (quietHoursEnd == null)) {
            "quiet hours need both a start and an end, or neither"
        }
    }

    val isActive: Boolean get() = leftAt == null

    /**
     * Replaces this member's own settings (`PUT …/members/me/settings`).
     *
     * Identity, role, the membership dates and [leftAt] are not the caller's to
     * change and are not in [MemberSettings] at all — which is stronger than
     * checking them, because there is nothing to check.
     *
     * `reminderTimezone` absent means the caller did not say, and the member
     * keeps the zone they had rather than falling back to the bond's anchor:
     * losing a zone you deliberately set, because you edited a nickname, is the
     * kind of quiet damage doc 04 §6 is about. Every other field is cleared
     * when absent, which is what a `PUT` means.
     */
    fun withSettings(settings: MemberSettings): Member =
        copy(
            nicknameForOther = settings.nicknameForOther,
            reminderTimeLocal = settings.reminderTimeLocal,
            reminderTimezone = settings.reminderTimezone ?: reminderTimezone,
            quietHoursStart = settings.quietHoursStart,
            quietHoursEnd = settings.quietHoursEnd,
        )

    companion object {
        /**
         * Chosen in the Phase 2 design (§5.2), not given by any FR — the same
         * status as the 80-character display name, and flagged for Daniel in
         * the same way.
         */
        const val MAX_NICKNAME_LENGTH = 40

        /** V9's column default, restated here so the schema and the domain agree by reading. */
        val DEFAULT_REMINDER_TIME: LocalTime = LocalTime.of(20, 0)

        /**
         * The person who created the bond (`POST /bonds`).
         *
         * Two factories rather than one taking a [MemberRole], because the
         * role is the only thing that differed between the two call sites and
         * a boolean-shaped parameter at a call site reads worse than a name.
         * Detekt's six-parameter limit made the choice, and it was right to:
         * `Member.owner(...)` says what is happening, `Member.join(...,
         * MemberRole.OWNER, ...)` makes the reader decode it.
         */
        fun owner(
            id: MemberId,
            bondId: BondId,
            userId: UserId,
            reminderTimezone: RegionZone,
            now: Instant,
        ): Member = member(id, bondId, userId, reminderTimezone, now).copy(role = MemberRole.OWNER)

        /**
         * The person who accepted an invite (`POST /invites/{code}/accept`,
         * slice B2).
         *
         * Notification settings start at their defaults rather than being
         * asked for at the door — `states.md` §1 caps onboarding at six
         * screens, and a reminder time is a settings row (§8), not a step.
         */
        fun member(
            id: MemberId,
            bondId: BondId,
            userId: UserId,
            reminderTimezone: RegionZone,
            now: Instant,
        ): Member =
            Member(
                id = id,
                bondId = bondId,
                userId = userId,
                role = MemberRole.MEMBER,
                joinedAt = now,
                leftAt = null,
                reminderTimeLocal = DEFAULT_REMINDER_TIME,
                reminderTimezone = reminderTimezone,
                quietHoursStart = null,
                quietHoursEnd = null,
                nicknameForOther = null,
            )
    }
}

/**
 * One member's own notification settings — the whole of what `PUT
 * /bonds/{bondId}/members/me/settings` replaces (doc 06 §3.3, `states.md` §8).
 *
 * All five fields together, because the endpoint is a `PUT`: it replaces, so an
 * absent field is a cleared field. That is a deliberate choice over a second
 * `PATCH` — these are five small fields on one screen, a client always holds all
 * of them, and "replace what is there" needs no [Change] wrapper and no
 * ambiguity about what a `null` meant.
 *
 * [reminderTimeLocal] has no default here on purpose:
 * [Member.DEFAULT_REMINDER_TIME] is the default for a *new* member, and a `PUT`
 * that omits the one field a member cannot be without should be told so rather
 * than silently set back to 20:00.
 */
internal data class MemberSettings(
    val nicknameForOther: String? = null,
    val reminderTimeLocal: LocalTime,
    val reminderTimezone: RegionZone? = null,
    val quietHoursStart: LocalTime? = null,
    val quietHoursEnd: LocalTime? = null,
)
