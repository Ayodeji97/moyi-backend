package com.moyi.bond.web

import com.moyi.bond.domain.Member
import com.moyi.bond.domain.MemberSettings
import com.moyi.bond.domain.RegionZone
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalTime

/**
 * The wire format of `PUT /bonds/{bondId}/members/me/settings`.
 *
 * A `PUT`, so this is the whole of the member's settings and an absent field is
 * a cleared one — with one exception: `reminderTimezone` keeps its current value
 * when absent, because silently resetting somebody's zone as a side effect of
 * editing a nickname is the kind of damage doc 04 §6 is about.
 *
 * `reminderTimeLocal` is required. It is the one field a member cannot be
 * without, and a `PUT` that omits it should be told so rather than quietly set
 * back to 20:00.
 */
internal data class MemberSettingsRequest(
    @field:Size(min = 1, max = Member.MAX_NICKNAME_LENGTH)
    @field:Pattern(regexp = "(?sU).*\\S.*", message = "must not be blank")
    val nicknameForOther: String? = null,
    @field:NotNull
    @field:Pattern(regexp = TIME_OF_DAY, message = "must be a time of day such as 21:00")
    val reminderTimeLocal: String? = null,
    @field:ValidRegionZone
    val reminderTimezone: String? = null,
    @field:Pattern(regexp = TIME_OF_DAY, message = "must be a time of day such as 22:00")
    val quietHoursStart: String? = null,
    @field:Pattern(regexp = TIME_OF_DAY, message = "must be a time of day such as 07:00")
    val quietHoursEnd: String? = null,
) {
    /**
     * Both quiet hours or neither — the aggregate's invariant, restated at the
     * edge so the answer is a `422` rather than a 500 from a `require` deeper
     * down.
     *
     * `BondConstraints` explains why the edge *delegates* to the domain wherever
     * it can be asked; this rule cannot be asked without building a `Member`, so
     * it is stated in both places and the aggregate keeps the last word.
     */
    @get:AssertTrue(message = "need both a start and an end, or neither")
    val quietHoursArePaired: Boolean get() = (quietHoursStart == null) == (quietHoursEnd == null)

    fun toSettings() =
        MemberSettings(
            nicknameForOther = nicknameForOther?.trim(),
            reminderTimeLocal = LocalTime.parse(reminderTimeLocal),
            reminderTimezone = reminderTimezone?.takeIf { it.isNotBlank() }?.let { RegionZone.of(it.trim()) },
            quietHoursStart = quietHoursStart?.let(LocalTime::parse),
            quietHoursEnd = quietHoursEnd?.let(LocalTime::parse),
        )

    private companion object {
        const val TIME_OF_DAY = "^([01]\\d|2[0-3]):[0-5]\\d$"
    }
}
