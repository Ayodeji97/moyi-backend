package com.moyi.bond.web

import com.moyi.bond.domain.Member
import java.time.format.DateTimeFormatter

/**
 * One member's own settings (doc 06 §3.3).
 *
 * The caller's, always: `states.md` §8 forbids showing the other member's, and
 * the route this is returned from has no member id in it. Times are `HH:mm`
 * like every other time of day in this API, formatted rather than serialised
 * because `LocalTime`'s own form is `07:30:00` (doc 06 §1).
 */
internal data class MemberSettingsResponse(
    val nicknameForOther: String?,
    val reminderTimeLocal: String,
    val reminderTimezone: String,
    val quietHoursStart: String?,
    val quietHoursEnd: String?,
) {
    companion object {
        private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun from(member: Member) =
            MemberSettingsResponse(
                nicknameForOther = member.nicknameForOther,
                reminderTimeLocal = HH_MM.format(member.reminderTimeLocal),
                reminderTimezone = member.reminderTimezone.id,
                quietHoursStart = member.quietHoursStart?.let(HH_MM::format),
                quietHoursEnd = member.quietHoursEnd?.let(HH_MM::format),
            )
    }
}
