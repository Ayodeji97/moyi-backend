package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

/** A stretch `[from, to)` in which a bond refused every write, and after which it took them again. */
internal data class WritePause(
    val from: Instant,
    val to: Instant,
) {
    /** Whether any of `[startsAt, endsAt)` fell inside this pause. */
    fun overlaps(
        startsAt: Instant,
        endsAt: Instant,
    ): Boolean = from.isBefore(endsAt) && startsAt.isBefore(to)
}

/**
 * `bond_write_pauses` (V19): written when a deletion that was counting down
 * is called off, read by the close job's view of a bond. Plain SQL on a
 * table nothing else maps, as [StrictModeChanges].
 */
@Component
internal class WritePauses(
    private val jdbc: JdbcTemplate,
) {
    /** The caller holds the bond's lock. A bond's countdowns cannot overlap, so one start is one row. */
    fun record(
        bondId: BondId,
        from: Instant,
        to: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO bond_write_pauses (bond_id, paused_from, paused_to) VALUES (?, ?, ?)
            ON CONFLICT (bond_id, paused_from) DO UPDATE SET paused_to = EXCLUDED.paused_to
            """.trimIndent(),
            bondId.value,
            Timestamp.from(from),
            Timestamp.from(to),
        )
    }

    fun of(bondId: BondId): List<WritePause> =
        jdbc.query(
            "SELECT paused_from, paused_to FROM bond_write_pauses WHERE bond_id = ? ORDER BY paused_from",
            { row, _ -> WritePause(row.getTimestamp("paused_from").toInstant(), row.getTimestamp("paused_to").toInstant()) },
            bondId.value,
        )
}
