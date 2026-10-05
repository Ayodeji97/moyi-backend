package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayOutcome
import com.moyi.gratitude.domain.StreakChange
import com.moyi.gratitude.domain.StreakState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One of a bond's days, as the streak's evaluation reads it. */
internal data class DayToEvaluate(
    val id: UUID,
    val date: LocalDate,
    val status: BondDayStatus,
    val endsAt: Instant,
    /** False for a day still open: evaluation stops in front of it. */
    val isClosed: Boolean,
)

/** One of a bond's days as it was evaluated: what `recalculate` replays. */
internal data class EvaluatedDay(
    val date: LocalDate,
    val outcome: DayOutcome,
    val strict: Boolean,
    val freezeApplied: Boolean,
)

/**
 * The streak's rows (V17): `streak_states`, `streak_events`, and the three
 * decision columns on `bond_days`.
 *
 * Plain SQL, like [CloseCandidates] and for the same reason: the evaluation
 * reads a few columns of rows it then updates by id, in a transaction that
 * loads no entity, and a managed `BondDayEntity` here would only be a stale
 * copy for somebody later to be handed.
 *
 * **[lock] is the evaluation's mutex.** Everything else here assumes the
 * caller holds it: two runs evaluating one bond would each fold from the same
 * state and both count the same days.
 */
@Component
internal class StreakStore(
    private val jdbc: JdbcTemplate,
) {
    /** Bonds with a day that is settled and not evaluated, after [after] in id order. Derived, never remembered. */
    fun bondsToEvaluate(
        after: UUID?,
        limit: Int,
    ): List<UUID> =
        jdbc.query(
            """
            SELECT DISTINCT bond_id FROM bond_days
            WHERE closed_at IS NOT NULL AND evaluated_at IS NULL AND bond_id > ?
            ORDER BY bond_id
            LIMIT ?
            """.trimIndent(),
            { row, _ -> row.getObject(1, UUID::class.java) },
            after ?: FIRST_BOND,
            limit,
        )

    /**
     * The bond's streak, with its row held until the transaction ends. The
     * row is made first if the bond has none: a lock needs a row to be on,
     * and `ON CONFLICT DO NOTHING` makes two first evaluations agree on one.
     */
    fun lock(
        bondId: UUID,
        now: Instant,
    ): StreakState {
        jdbc.update(
            "INSERT INTO streak_states (bond_id, updated_at) VALUES (?, ?) ON CONFLICT (bond_id) DO NOTHING",
            bondId,
            Timestamp.from(now),
        )
        return checkNotNull(
            jdbc.query("$SELECT_STATE WHERE bond_id = ? FOR UPDATE", { row, _ -> row.toState() }, bondId).singleOrNull(),
        ) { "a streak row just inserted must exist: $bondId" }
    }

    /** The bond's streak as it stands, or `null` if none of its days has been evaluated. No lock. */
    fun find(bondId: UUID): StreakState? = jdbc.query("$SELECT_STATE WHERE bond_id = ?", { row, _ -> row.toState() }, bondId).singleOrNull()

    /** Every day of the bond not yet evaluated, settled or not, oldest first. */
    fun unevaluatedDays(bondId: UUID): List<DayToEvaluate> =
        jdbc.query(
            "SELECT id, date, status, ends_at, closed_at FROM bond_days WHERE bond_id = ? AND evaluated_at IS NULL ORDER BY date",
            { row, _ ->
                DayToEvaluate(
                    row.getObject("id", UUID::class.java),
                    row.getObject("date", LocalDate::class.java),
                    BondDayStatus.valueOf(row.getString("status")),
                    row.getTimestamp("ends_at").toInstant(),
                    row.getTimestamp("closed_at") != null,
                )
            },
            bondId,
        )

    /** The date of the last day of the bond that has been evaluated, or `null`. */
    fun lastEvaluatedDate(bondId: UUID): LocalDate? =
        jdbc.queryForObject("SELECT max(date) FROM bond_days WHERE bond_id = ? AND evaluated_at IS NOT NULL", LocalDate::class.java, bondId)

    /** Every evaluated day of the bond with what was decided for it, oldest first. */
    fun evaluatedDays(bondId: UUID): List<EvaluatedDay> =
        jdbc.query(
            """
            SELECT date, evaluated_as, evaluated_strict, freeze_applied FROM bond_days
            WHERE bond_id = ? AND evaluated_at IS NOT NULL ORDER BY date
            """.trimIndent(),
            { row, _ ->
                EvaluatedDay(
                    row.getObject("date", LocalDate::class.java),
                    DayOutcome.valueOf(row.getString("evaluated_as")),
                    row.getBoolean("evaluated_strict"),
                    row.getBoolean("freeze_applied"),
                )
            },
            bondId,
        )

    /**
     * Records what was decided for one day. A missed day that spent a freeze
     * becomes `FROZEN` here — the one change evaluation makes to a settled
     * day's status (BR-5), made once. `version` moves with it, as it does on
     * every other write of the row.
     */
    fun recordDay(
        dayId: UUID,
        outcome: DayOutcome,
        strict: Boolean,
        freezeApplied: Boolean,
        now: Instant,
    ) {
        val updated =
            jdbc.update(
                """
                UPDATE bond_days
                SET evaluated_at = ?, evaluated_as = ?, evaluated_strict = ?, freeze_applied = ?,
                    status = CASE WHEN ? THEN 'FROZEN' ELSE status END, version = version + 1
                WHERE id = ? AND evaluated_at IS NULL
                """.trimIndent(),
                Timestamp.from(now),
                outcome.name,
                strict,
                freezeApplied,
                freezeApplied,
                dayId,
            )
        check(updated == 1) { "a bond-day is evaluated once, under its bond's streak lock: $dayId" }
    }

    fun save(
        bondId: UUID,
        state: StreakState,
        now: Instant,
        recomputed: Boolean = false,
    ) {
        jdbc.update(
            """
            UPDATE streak_states
            SET current_streak = ?, longest_streak = ?, last_complete_date = ?, freezes_available = ?,
                freeze_progress = ?, freezes_consumed = ?, total_complete_days = ?, updated_at = ?,
                recomputed_at = CASE WHEN ? THEN ? ELSE recomputed_at END
            WHERE bond_id = ?
            """.trimIndent(),
            state.current,
            state.longest,
            state.lastCompleteDate,
            state.freezesAvailable,
            state.freezeProgress,
            state.freezesConsumed,
            state.totalCompleteDays,
            Timestamp.from(now),
            recomputed,
            Timestamp.from(now),
            bondId,
        )
    }

    /** One line of the audit log: what [date] did to the run. */
    @Suppress("LongParameterList") // The row's own columns.
    fun appendEvent(
        id: UUID,
        bondId: UUID,
        date: LocalDate,
        event: String,
        before: Int,
        after: Int,
        now: Instant,
    ) {
        jdbc.update(
            "INSERT INTO streak_events (id, bond_id, date, event, streak_before, streak_after, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id,
            bondId,
            date,
            event,
            before,
            after,
            Timestamp.from(now),
        )
    }

    private fun ResultSet.toState(): StreakState =
        StreakState(
            current = getInt("current_streak"),
            longest = getInt("longest_streak"),
            lastCompleteDate = getObject("last_complete_date", LocalDate::class.java),
            freezesAvailable = getInt("freezes_available"),
            freezeProgress = getInt("freeze_progress"),
            freezesConsumed = getInt("freezes_consumed"),
            totalCompleteDays = getInt("total_complete_days"),
        )

    internal companion object {
        private const val SELECT_STATE =
            "SELECT current_streak, longest_streak, last_complete_date, freezes_available, freeze_progress, " +
                "freezes_consumed, total_complete_days FROM streak_states"
        private val FIRST_BOND = UUID(0, 0)

        /** [StreakChange] as `streak_events.event` spells it; `null` for a day that changed nothing. */
        fun eventOf(change: StreakChange): String? = change.takeUnless { it == StreakChange.NONE }?.name

        const val FREEZE_BANKED = "FREEZE_BANKED"
    }
}
