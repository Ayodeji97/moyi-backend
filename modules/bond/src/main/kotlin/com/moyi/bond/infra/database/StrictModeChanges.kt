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
     * caller holds the bond's lock, and calls this for a real change only.
     *
     * **A second change stamped with the same instant removes the first.**
     * Every row is a flip, so two at one instant are a flip and a flip back:
     * no change at that instant at all. Keeping the later value in the one
     * row would leave a row that changed nothing, and the history reads the
     * setting before its first row as the opposite of that row.
     */
    fun record(
        bondId: BondId,
        at: Instant,
        strictMode: Boolean,
    ) {
        val stamp = Timestamp.from(at)
        val undone = jdbc.update("DELETE FROM bond_strict_mode_changes WHERE bond_id = ? AND changed_at = ?", bondId.value, stamp)
        if (undone == 0) {
            jdbc.update(
                "INSERT INTO bond_strict_mode_changes (bond_id, changed_at, strict_mode) VALUES (?, ?, ?)",
                bondId.value,
                stamp,
                strictMode,
            )
        }
    }

    /** The bond's changes, oldest first. */
    fun of(bondId: BondId): List<StrictModeChange> =
        jdbc.query(
            "SELECT changed_at, strict_mode FROM bond_strict_mode_changes WHERE bond_id = ? ORDER BY changed_at",
            { row, _ -> StrictModeChange(row.getTimestamp("changed_at").toInstant(), row.getBoolean("strict_mode")) },
            bondId.value,
        )
}
