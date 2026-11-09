package com.moyi.export.infra.database

import com.moyi.export.domain.DataExport
import com.moyi.export.domain.DataExportStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** `data_exports` (V53). Every write here is conditional on the status it expects, so two writers cannot both win. */
@Component
@Suppress("TooManyFunctions") // One statement per transition of a small state machine, each named for it.
internal class DataExports(
    private val jdbc: JdbcTemplate,
) {
    /**
     * A new PREPARING export for [userId], unless one is already being prepared: V53's partial unique
     * index makes two at once one row, and the loser inserts nothing. Returns whether it inserted.
     */
    fun insertIfNonePreparing(
        id: UUID,
        userId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            INSERT INTO data_exports (id, user_id, status, requested_at) VALUES (?, ?, 'PREPARING', ?)
            ON CONFLICT (user_id) WHERE status = 'PREPARING' DO NOTHING
            """.trimIndent(),
            id,
            userId,
            Timestamp.from(at),
        ) == 1

    fun preparingOf(userId: UUID): DataExport? = jdbc.query("$SELECT WHERE user_id = ? AND status = 'PREPARING'", ROW, userId).firstOrNull()

    /** The person's export [id]; null for one that is not theirs, exactly as for one that does not exist. */
    fun find(
        userId: UUID,
        id: UUID,
    ): DataExport? = jdbc.query("$SELECT WHERE id = ? AND user_id = ?", ROW, id, userId).firstOrNull()

    fun latestOf(userId: UUID): DataExport? =
        jdbc.query("$SELECT WHERE user_id = ? ORDER BY requested_at DESC, id DESC LIMIT 1", ROW, userId).firstOrNull()

    /** PREPARING exports no build holds at [now], oldest first. */
    fun claimable(
        now: Instant,
        limit: Int,
    ): List<DataExport> =
        jdbc.query(
            "$SELECT WHERE status = 'PREPARING' AND (claimed_until IS NULL OR claimed_until <= ?) ORDER BY requested_at, id LIMIT ?",
            ROW,
            Timestamp.from(now),
            limit,
        )

    /** Takes [id] for a build until [until], counting the attempt. The attempt's number, or null if it was not free. */
    fun claim(
        id: UUID,
        now: Instant,
        until: Instant,
    ): Int? =
        jdbc
            .queryForList(
                """
                UPDATE data_exports SET attempts = attempts + 1, claimed_until = ?
                WHERE id = ? AND status = 'PREPARING' AND (claimed_until IS NULL OR claimed_until <= ?)
                RETURNING attempts
                """.trimIndent(),
                Int::class.java,
                Timestamp.from(until),
                id,
                Timestamp.from(now),
            ).firstOrNull()

    /** PREPARING to READY with its archive. False if it is no longer being prepared (an account deletion took it). */
    @Suppress("LongParameterList") // The archive's four facts.
    fun ready(
        id: UUID,
        key: String,
        sizeBytes: Long,
        readyAt: Instant,
        expiresAt: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE data_exports SET status = 'READY', storage_key = ?, size_bytes = ?, ready_at = ?, expires_at = ?, claimed_until = NULL
            WHERE id = ? AND status = 'PREPARING'
            """.trimIndent(),
            key,
            sizeBytes,
            Timestamp.from(readyAt),
            Timestamp.from(expiresAt),
            id,
        ) == 1

    /** A failed attempt gives its claim back, so the next run may try again. */
    fun release(id: UUID) {
        jdbc.update("UPDATE data_exports SET claimed_until = NULL WHERE id = ? AND status = 'PREPARING'", id)
    }

    fun fail(
        id: UUID,
        at: Instant,
    ) {
        jdbc.update(
            "UPDATE data_exports SET status = 'FAILED', ended_at = ?, claimed_until = NULL WHERE id = ? AND status = 'PREPARING'",
            Timestamp.from(at),
            id,
        )
    }

    /** READY exports whose 24 hours were over by [now], soonest first. */
    fun expired(
        now: Instant,
        limit: Int,
    ): List<DataExport> =
        jdbc.query("$SELECT WHERE status = 'READY' AND expires_at <= ? ORDER BY expires_at, id LIMIT ?", ROW, Timestamp.from(now), limit)

    /** READY to EXPIRED, its key forgotten. */
    fun expire(
        id: UUID,
        at: Instant,
    ): Boolean =
        jdbc.update(
            "UPDATE data_exports SET status = 'EXPIRED', storage_key = NULL, ended_at = ? WHERE id = ? AND status = 'READY'",
            Timestamp.from(at),
            id,
        ) == 1

    /** Every archive key [userId]'s exports still hold. */
    fun keysOf(userId: UUID): List<String> =
        jdbc
            .queryForList("SELECT storage_key FROM data_exports WHERE user_id = ? AND storage_key IS NOT NULL", String::class.java, userId)
            .filterNotNull()

    /** Every export of [userId], gone; how many. */
    fun deleteAllOf(userId: UUID): Int = jdbc.update("DELETE FROM data_exports WHERE user_id = ?", userId)

    private companion object {
        const val SELECT =
            "SELECT id, user_id, status, requested_at, ready_at, expires_at, storage_key, size_bytes, attempts FROM data_exports"

        val ROW =
            RowMapper { rs, _ ->
                DataExport(
                    id = rs.getObject("id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    stored = DataExportStatus.valueOf(rs.getString("status")),
                    requestedAt = rs.getTimestamp("requested_at").toInstant(),
                    readyAt = rs.getTimestamp("ready_at")?.toInstant(),
                    expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                    storageKey = rs.getString("storage_key"),
                    sizeBytes = rs.getObject("size_bytes", java.lang.Long::class.java)?.toLong(),
                    attempts = rs.getInt("attempts"),
                )
            }
    }
}
