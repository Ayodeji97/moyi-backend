package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.CurrentUser
import com.moyi.common.web.NotFoundException
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.service.GetStreak
import com.moyi.gratitude.service.StreakDetail
import com.moyi.gratitude.service.StreakView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * `GET /bonds/{bondId}/streak` (spec §5.2, doc 06 §3.4): the streak, and a
 * year of the bond's calendar for the heatmap.
 *
 * The guard first, as on every bond-scoped route: a non-member, an unknown
 * id and a value that is not a UUID get one `404` (T-02). A member who has
 * left can still read it — an ended bond keeps its record (doc 04 §8.3).
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class StreakController(
    private val access: BondAccess,
    private val getStreak: GetStreak,
) {
    // Named `streak`: the method name is the API's operationId (see EntriesController).
    @GetMapping("/{bondId}/streak")
    fun streak(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): StreakResponse {
        val id = runCatching { UUID.fromString(bondId) }.getOrElse { throw NotFoundException("That bond was not found.") }
        return StreakResponse.from(getStreak.detail(access.membershipOf(caller.id, id)))
    }
}

/**
 * The streak, and the calendar behind it.
 *
 * **Nothing here says who wrote and who did not** (FR-076). The fields are
 * counts about the bond; [days] carries each day's status, which is what
 * `GET /today` already shares about today (spec §4, "what is deliberately
 * shared"). There is no per-member field, and none may be added.
 */
internal data class StreakResponse(
    val current: Int,
    val longest: Int,
    val freezesAvailable: Int,
    /** Complete days since the last freeze was earned, 0 to 13: fourteen earn the next (BR-5). */
    val freezeProgress: Int,
    val strictMode: Boolean,
    val totalCompleteDays: Int,
    val lastCompleteDate: LocalDate?,
    /** The last 53 weeks at most, oldest first, from the day the bond became two people. A date with no row is absent. */
    val days: List<StreakDayResponse>,
) {
    companion object {
        fun from(detail: StreakDetail): StreakResponse =
            StreakResponse(
                current = detail.streak.current,
                longest = detail.streak.longest,
                freezesAvailable = detail.streak.freezesAvailable,
                freezeProgress = detail.streak.freezeProgress,
                strictMode = detail.streak.strictMode,
                totalCompleteDays = detail.streak.totalCompleteDays,
                lastCompleteDate = detail.streak.lastCompleteDate,
                days = detail.days.map { StreakDayResponse(it.date, it.status) },
            )
    }
}

internal data class StreakDayResponse(
    val date: LocalDate,
    val status: BondDayStatus,
)

/** The `streak` object of `GET /today` (doc 06 §3.4): the four numbers the home screen shows. */
internal data class TodayStreakResponse(
    val current: Int,
    val longest: Int,
    val freezesAvailable: Int,
    val strictMode: Boolean,
) {
    companion object {
        fun from(view: StreakView): TodayStreakResponse =
            TodayStreakResponse(view.current, view.longest, view.freezesAvailable, view.strictMode)
    }
}
