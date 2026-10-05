package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.StrictModeChange
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

/**
 * `bond_strict_mode_changes` (V18): written when a member really changes a
 * bond's Strict mode, read by the close job's view of a bond. Two statements
 * on a table nothing else maps, so plain SQL and no entity.
 */
@Component
internal class StrictModeChanges(
    private val jdbc: JdbcTemplate,
) {
    /**
     * Records that [bondId]'s Strict mode became [strictMode] at [at]. The
     * caller holds the bond's lock. Two changes stamped with one instant are
     * one change to whichever came last, which is what the bond then holds.
     */
    fun record(
        bondId: BondId,
        at: Instant,
        strictMode: Boolean,
    ) {
        jdbc.update(
            """
            INSERT INTO bond_strict_mode_changes (bond_id, changed_at, strict_mode) VALUES (?, ?, ?)
            ON CONFLICT (bond_id, changed_at) DO UPDATE SET strict_mode = EXCLUDED.strict_mode
            """.trimIndent(),
            bondId.value,
            Timestamp.from(at),
            strictMode,
        )
    }

    /** The bond's changes, oldest first. */
    fun of(bondId: BondId): List<StrictModeChange> =
        jdbc.query(
            "SELECT changed_at, strict_mode FROM bond_strict_mode_changes WHERE bond_id = ? ORDER BY changed_at",
            { row, _ -> StrictModeChange(row.getTimestamp("changed_at").toInstant(), row.getBoolean("strict_mode")) },
            bondId.value,
        )
}
