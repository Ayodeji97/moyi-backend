package com.moyi.gratitude.domain

import java.time.LocalDate

/**
 * A bond's streak, as of the last of its days that has been evaluated (spec
 * §3.3, §6.5; doc 07 §2's `streak_states`). A projection: everything here can
 * be worked out again from the bond's days and what was decided for each
 * (FR-074), which is what [StreakRules.step] is for.
 *
 * [current] is the run **through the last evaluated day**. A read adds today
 * when today is complete and directly follows it (`GetStreak`); nothing in
 * this type knows what today is.
 */
internal data class StreakState(
    val current: Int,
    /** Never decreases (FR-071). */
    val longest: Int,
    /** The date of the last day both wrote on, or `null` before the first. */
    val lastCompleteDate: LocalDate?,
    /** Banked freezes, at most [StreakRules.MAX_FREEZES]. */
    val freezesAvailable: Int,
    /** Complete days since the last threshold, `0 until` [StreakRules.DAYS_PER_FREEZE]. */
    val freezeProgress: Int,
    val freezesConsumed: Int,
    val totalCompleteDays: Int,
) {
    init {
        require(current >= 0 && longest >= current) { "a streak's longest run is at least its current one" }
        require(freezesAvailable in 0..StreakRules.MAX_FREEZES) { "at most ${StreakRules.MAX_FREEZES} freezes are banked" }
        require(freezeProgress in 0 until StreakRules.DAYS_PER_FREEZE) { "freeze progress resets at the threshold" }
    }

    companion object {
        /** A bond none of whose days has been evaluated. */
        val NONE = StreakState(0, 0, null, 0, 0, 0, 0)
    }
}

/**
 * What a settled day is, to the streak (spec §3.1's table, "counts toward the
 * streak"). Four answers, where a day has eight statuses, because the streak
 * asks a narrower question than the status answers.
 */
internal enum class DayOutcome {
    /** Both wrote (`REVEALED`): extends the run, and is a *complete* day (FR-070) toward the next freeze. */
    COMPLETE,

    /**
     * A day already `FROZEN` when it is evaluated: a date an approved zone
     * change stepped over (BR-6, doc 04 §8.5). Extends the run. Not a
     * complete day — nobody wrote on it — so it earns nothing toward a freeze.
     */
    FROZEN_BY_SKIP,

    /** `SOLO` or `EMPTY`: spends a freeze, or ends the run. */
    MISSED,

    /** Excluded from evaluation (doc 04 §8.1, §8.2, §8.3a): neither extends the run nor ends it. */
    SUSPENDED,

    /**
     * A day **missed** after its bond had stopped taking writes (doc 04
     * §8.3): "the streak freezes rather than breaks — it is preserved at its
     * value". It moves nothing. Only a missed day: one both wrote on before
     * the bond ended that day is [COMPLETE]. Decided by the evaluation,
     * which knows when the bond ended; no status says it.
     */
    AFTER_THE_END,
    ;

    companion object {
        /** `null` for a status no settled day has. */
        fun of(status: BondDayStatus): DayOutcome? =
            when (status) {
                BondDayStatus.REVEALED -> COMPLETE
                BondDayStatus.FROZEN -> FROZEN_BY_SKIP
                BondDayStatus.SOLO, BondDayStatus.EMPTY -> MISSED
                BondDayStatus.SUSPENDED -> SUSPENDED
                BondDayStatus.OPEN, BondDayStatus.PARTIAL, BondDayStatus.PENDING_REVEAL -> null
            }
    }
}

/**
 * What one square of a bond's calendar says. **Fewer things than a day's
 * status, on purpose.** The design system's rule for this screen is that a
 * solo day is not drawn: "a month-long ledger of days when exactly one
 * person wrote is a durable inference surface: you know your own history, so
 * every Solo cell resolves to a partner miss" (`states.md` §7). `GET /today`
 * shares today's status because the product needs it today; a year of them
 * is a different thing. So a day one wrote on and a day nobody wrote on are
 * the same square, and so is today until it is complete.
 */
internal enum class StreakCell {
    /** Both wrote. */
    COMPLETE,

    /** A rest day: a freeze covered it, or a zone change stepped over the date. Counts toward the run. */
    FROZEN,

    /** The run did not include this day. Whether one wrote or neither is not said. */
    MISSED,

    /** Today, not complete yet. Whether anybody has written is `GET /today`'s to say, not this calendar's. */
    OPEN,
    ;

    companion object {
        /**
         * The square for a day with [status] that was evaluated as [outcome]
         * (`null`: not evaluated yet), or `null` for a day the calendar does
         * not draw:
         *
         * - a day not yet evaluated, unless it is today — its square could
         *   still change (a missed day may become a rest day), and "the
         *   numbers must agree with the grid": the run counts evaluated days;
         * - a suspended day, and a missed day after the bond had ended, which
         *   moved nothing and must not sit in the grid looking like a break.
         */
        fun of(
            status: BondDayStatus,
            outcome: DayOutcome?,
            isToday: Boolean,
        ): StreakCell? =
            when {
                isToday && outcome == null -> if (status == BondDayStatus.REVEALED) COMPLETE else OPEN
                outcome == DayOutcome.COMPLETE -> COMPLETE
                outcome == DayOutcome.FROZEN_BY_SKIP -> FROZEN
                outcome == DayOutcome.MISSED -> if (status == BondDayStatus.FROZEN) FROZEN else MISSED
                else -> null
            }
    }
}

/** One square of a bond's calendar. */
internal data class StreakDay(
    val date: LocalDate,
    val cell: StreakCell,
)

/** What one day did to the run — the vocabulary of `streak_events`, and of "why did my streak break?". */
internal enum class StreakChange {
    EXTENDED,

    /** A missed day covered by a banked freeze: the run goes on and the day becomes `FROZEN`. */
    FREEZE_CONSUMED,

    /** A missed day with nothing to cover it, on a run that was not already zero. */
    BROKEN,

    /** Nothing moved: a suspended day, a day missed after the end, or a missed or stepped-over day on a run already at zero. */
    NONE,
}

/** The state after one day, and what was decided for that day. */
internal data class Evaluation(
    val state: StreakState,
    /** True when this day spent a freeze. Stored on the day: a replay must not decide it again. */
    val freezeApplied: Boolean,
    val change: StreakChange,
    /** True when this day reached the threshold and banked a freeze. */
    val freezeBanked: Boolean,
)

/**
 * The streak's rules (spec §6.5), as one step of a fold over a bond's settled
 * days in date order. Pure: no clock, no store, no bond — which is what lets
 * the properties in §6.5 be tested over thousands of timelines, and what
 * makes `recalculate` the same code as the nightly evaluation rather than a
 * second statement of it.
 *
 * **Freezes accrue incrementally and this is not a formula (BR-5).** The
 * fourteenth complete day resets progress, and banks a freeze only if Strict
 * mode was off *as that day ended* and fewer than [MAX_FREEZES] are banked.
 * `floor(totalCompleteDays / 14) - freezesConsumed` would hand a couple the
 * freezes for every fortnight they spent in Strict mode the moment they
 * switched it off, and FR-073 says toggling Strict mode never alters a past
 * day.
 */
internal object StreakRules {
    const val DAYS_PER_FREEZE = 14
    const val MAX_FREEZES = 2

    /**
     * @param strict whether the bond was in Strict mode as this day ended —
     * the stored value on a replay, never the setting at the time of asking
     * @param recordedFreeze `null` to decide whether a missed day spends a
     * freeze; the decision already made, when replaying (FR-074). A replay
     * that is told a freeze was spent spends one whatever [strict] now says.
     */
    fun step(
        state: StreakState,
        date: LocalDate,
        outcome: DayOutcome,
        strict: Boolean,
        recordedFreeze: Boolean? = null,
    ): Evaluation =
        when (outcome) {
            DayOutcome.SUSPENDED, DayOutcome.AFTER_THE_END -> {
                Evaluation(
                    state,
                    freezeApplied = false,
                    StreakChange.NONE,
                    freezeBanked = false,
                )
            }

            DayOutcome.COMPLETE -> {
                complete(state, date, strict)
            }

            // A rest day keeps a run going; it does not start one. From zero
            // it would be a streak of one with no day anybody wrote on.
            DayOutcome.FROZEN_BY_SKIP -> {
                if (state.current > 0) {
                    Evaluation(extended(state), freezeApplied = false, StreakChange.EXTENDED, freezeBanked = false)
                } else {
                    Evaluation(state, freezeApplied = false, StreakChange.NONE, freezeBanked = false)
                }
            }

            DayOutcome.MISSED -> {
                missed(state, strict, recordedFreeze)
            }
        }

    private fun extended(state: StreakState): StreakState {
        val current = state.current + 1
        return state.copy(current = current, longest = maxOf(state.longest, current))
    }

    private fun complete(
        state: StreakState,
        date: LocalDate,
        strict: Boolean,
    ): Evaluation {
        val atThreshold = state.freezeProgress + 1 == DAYS_PER_FREEZE
        val banks = atThreshold && !strict && state.freezesAvailable < MAX_FREEZES
        val next =
            extended(state).copy(
                lastCompleteDate = date,
                totalCompleteDays = state.totalCompleteDays + 1,
                freezeProgress = if (atThreshold) 0 else state.freezeProgress + 1,
                freezesAvailable = state.freezesAvailable + if (banks) 1 else 0,
            )
        return Evaluation(next, freezeApplied = false, StreakChange.EXTENDED, freezeBanked = banks)
    }

    private fun missed(
        state: StreakState,
        strict: Boolean,
        recordedFreeze: Boolean?,
    ): Evaluation {
        // A freeze saves a run. With no run to save it is kept: spent on a
        // run of zero it "extended" the streak to one, on a day nobody wrote.
        val spends = recordedFreeze ?: (!strict && state.freezesAvailable > 0 && state.current > 0)
        return if (spends) {
            check(state.freezesAvailable > 0) { "a day cannot have spent a freeze the bond did not have" }
            val next = extended(state).copy(freezesAvailable = state.freezesAvailable - 1, freezesConsumed = state.freezesConsumed + 1)
            Evaluation(next, freezeApplied = true, StreakChange.FREEZE_CONSUMED, freezeBanked = false)
        } else {
            val change = if (state.current > 0) StreakChange.BROKEN else StreakChange.NONE
            Evaluation(state.copy(current = 0), freezeApplied = false, change, freezeBanked = false)
        }
    }
}
