package com.moyi.bond.web

import com.moyi.notification.api.NotificationPreferences
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull

/**
 * The body of `PUT /bonds/{bondId}/members/me/notifications`.
 *
 * `categories` is required; each toggle in it is optional, and **an absent
 * toggle keeps its current value**. That is the reason this resource exists
 * apart from the settings `PUT` (spec §4 N2): a client written before a toggle
 * existed must not turn it off by not knowing it.
 */
internal data class MemberNotificationsRequest(
    @field:NotNull
    @field:Valid
    val categories: NotificationCategoriesRequest? = null,
) {
    fun applyTo(current: NotificationPreferences): NotificationPreferences {
        val asked = checkNotNull(categories) { "validated as present" }
        return NotificationPreferences(
            reminder = asked.reminder ?: current.reminder,
            partnerJoined = asked.partnerJoined ?: current.partnerJoined,
            reveal = asked.reveal ?: current.reveal,
            milestone = asked.milestone ?: current.milestone,
            onThisDay = asked.onThisDay ?: current.onThisDay,
            proposal = asked.proposal ?: current.proposal,
        )
    }
}

/**
 * The toggles a `PUT` may set, each optional (FR-081, ADR-0045).
 *
 * - `reminder`: the daily reminder to write (FR-080).
 * - `partnerJoined`: the other person accepted the invite.
 * - `reveal`: the day's entries opened, both written or one (Q8).
 * - `milestone`: 7, 30, 100 or 365 days you both wrote (ADR-0041).
 * - `onThisDay`: an earlier year's entry on this date, at most weekly. Off until turned on (FR-092).
 * - `proposal`: a change the other member proposed, waiting on you (Q13).
 */
internal data class NotificationCategoriesRequest(
    val reminder: Boolean? = null,
    val partnerJoined: Boolean? = null,
    val reveal: Boolean? = null,
    val milestone: Boolean? = null,
    val onThisDay: Boolean? = null,
    val proposal: Boolean? = null,
)

/** The caller's toggles in one bond, every one of them: a member who never chose is shown the defaults. */
internal data class MemberNotificationsResponse(
    val categories: NotificationCategoriesResponse,
) {
    companion object {
        fun from(preferences: NotificationPreferences) =
            MemberNotificationsResponse(
                NotificationCategoriesResponse(
                    reminder = preferences.reminder,
                    partnerJoined = preferences.partnerJoined,
                    reveal = preferences.reveal,
                    milestone = preferences.milestone,
                    onThisDay = preferences.onThisDay,
                    proposal = preferences.proposal,
                ),
            )
    }
}

/** Every toggle, always present. */
internal data class NotificationCategoriesResponse(
    val reminder: Boolean,
    val partnerJoined: Boolean,
    val reveal: Boolean,
    val milestone: Boolean,
    val onThisDay: Boolean,
    val proposal: Boolean,
)
