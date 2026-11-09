package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema

private const val STREAK_RESPONSE = "StreakResponse"
private const val STREAK_DAY_RESPONSE = "StreakDayResponse"
private const val LAST_RUN_LENGTH = "lastRunLength"
private const val LAST_RUN_BROKEN_ON = "lastRunBrokenOn"
private const val REASON = "reason"
private const val SETTLES_AT = "settlesAt"
private const val BOND_DAY_RESPONSE = "BondDayResponse"

/**
 * `GET /bonds/{bondId}/streak`'s two additions of issue #141 (ADR-0034, as amended 2026-11-08):
 * the run the latest break ended, and why a `FROZEN` square is a rest day. Each is **always
 * sent**, `null` when there is none, so they are `required` and nullable: a client can tell "no
 * run has ended" from a field the server left out. The fields that were there before are left
 * as they were documented; making them `required` changes a generated client's types, and is
 * not this change's to make. `OpenApiContractTest` holds these sentences.
 */
internal fun documentStreak(api: OpenAPI) {
    api.components.schemas[STREAK_RESPONSE]?.let { streak ->
        listOf(LAST_RUN_LENGTH, LAST_RUN_BROKEN_ON)
            .filter { it !in streak.required.orEmpty() }
            .forEach(streak::addRequiredItem)
        streak.properties?.get(LAST_RUN_LENGTH)?.description = LAST_RUN_LENGTH_DESCRIPTION
        streak.properties?.get(LAST_RUN_BROKEN_ON)?.description = LAST_RUN_BROKEN_ON_DESCRIPTION
    }
    api.components.schemas[STREAK_DAY_RESPONSE]?.let { day ->
        if (REASON !in day.required.orEmpty()) day.addRequiredItem(REASON)
        @Suppress("UNCHECKED_CAST") // A schema's `enum` is typed by the schema; this one is read as untyped.
        val reason = day.properties?.get(REASON) as Schema<Any?>? ?: return
        reason.description = REASON_DESCRIPTION
        // As the entry's `reaction`: a nullable enum lists null among its values, or a validator
        // that reads `enum` strictly refuses the null this field is sent on every other square.
        reason.enum = reason.enum.orEmpty().filterNotNull() + null
        day.properties?.get(SETTLES_AT)?.description = SQUARE_SETTLES_AT_DESCRIPTION
    }
    // Issue #132 (ADR-0033 decision 15): GET /today's day says when it settles.
    api.components.schemas[BOND_DAY_RESPONSE]
        ?.properties
        ?.get(SETTLES_AT)
        ?.description = TODAY_SETTLES_AT_DESCRIPTION
}

private const val LAST_RUN_LENGTH_DESCRIPTION =
    "The length of the run the latest break ended, or null while no run has ended: \"your streak ended at 21 days\". " +
        "A run ends on a missed day no rest day covered; a covered day, a day missed with no run, a suspended day and a " +
        "day missed after the bond ended end nothing. It stays the last ended run while the next one grows, so the ended " +
        "copy is for when current is 0 and this is not null. Never today: today can only extend a run. It is not longest, " +
        "which is the longest run ever. At least 1."

private const val LAST_RUN_BROKEN_ON_DESCRIPTION =
    "The date of the missed day that ended the run lastRunLength counts, in the bond's calendar; that day is not part " +
        "of the run. Null exactly when lastRunLength is."

private const val REASON_DESCRIPTION =
    "Why a FROZEN square is a rest day, and null on every other square. REST_DAY: a day the two did not both write on, " +
        "covered by a banked freeze (\"we used a rest day\"). TIMEZONE_SHIFT: a date an approved time-zone change stepped " +
        "over, which nobody could write on; no freeze was spent."

private const val SQUARE_SETTLES_AT_DESCRIPTION =
    "The earliest an OPEN square's day can be decided, and null on every other square (issue #132): its end on the " +
        "bond's calendar plus the late-entry grace (twelve hours by default). An entry written on that day and sent " +
        "before this instant still counts for it. The day is decided (complete, a rest day or missed) on the next run " +
        "of the close job after this, usually within minutes, and the square stays OPEN until then, so this can be in " +
        "the past. An OPEN square is today, or an earlier day not yet decided."

private const val TODAY_SETTLES_AT_DESCRIPTION =
    "The earliest this day can be decided: its end on the bond's calendar plus the late-entry grace (twelve hours by " +
        "default; issue #132). An entry written on this day and sent before then counts for it; one sent after counts " +
        "for the day it is sent. The day is decided on the next run of the close job after this, usually within " +
        "minutes; until then a lone entry is not yet unlocked to the partner, and a missed day not yet counted. Never " +
        "null."
