package com.moyi.scheduling.infra

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * ShedLock's rows, read for the jobs page (ADR-0067 decision 1). The table's timestamps are zoneless
 * UTC (V16), so they are read `AT TIME ZONE 'UTC'` into instants, and "held" is decided by the database
 * against `timezone('utc', now())`, the clock ShedLock itself uses: never this JVM's clock.
 *
 * Read only. Nothing here takes, extends or frees a lock; that is ShedLock's.
 */
@Component
internal class LockRows(
    private val jdbc: JdbcTemplate,
) {
    fun all(): Map<String, LockRow> =
        jdbc
            .query(
                """
                SELECT name, locked_at AT TIME ZONE 'UTC' AS locked_at, lock_until AT TIME ZONE 'UTC' AS lock_until,
                       lock_until > timezone('utc', now()) AS held
                FROM shedlock
                """.trimIndent(),
            ) { rs, _ ->
                LockRow(
                    name = rs.getString("name"),
                    lockedAt = rs.getTimestamp("locked_at").toInstant(),
                    lockUntil = rs.getTimestamp("lock_until").toInstant(),
                    held = rs.getBoolean("held"),
                )
            }.associateBy { it.name }

    class LockRow(
        val name: String,
        val lockedAt: Instant,
        val lockUntil: Instant,
        val held: Boolean,
    )
}
