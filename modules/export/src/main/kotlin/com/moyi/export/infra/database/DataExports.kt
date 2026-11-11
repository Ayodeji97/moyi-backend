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

    /** PREPARING exports no build holds at [now] and with attempts left, oldest first. */
    fun claimable(
        now: Instant,
        maxAttempts: Int,
        limit: Int,
    ): List<DataExport> =
        jdbc.query(
            "$SELECT WHERE status = 'PREPARING' AND (claimed_until IS NULL OR claimed_until <= ?) AND attempts < ? " +
                "ORDER BY requested_at, id LIMIT ?",
            ROW,
            Timestamp.from(now),
            maxAttempts,
            limit,
        )

    /**
     * Every PREPARING export no build holds that has used its [maxAttempts], made FAILED: the last
     * attempt's process died before it could say so (a crash, or an error nothing caught). How many.
     */
    fun failExhausted(
        now: Instant,
        maxAttempts: Int,
    ): Int =
        jdbc.update(
            """
            UPDATE data_exports SET status = 'FAILED', ended_at = ?, claimed_until = NULL
            WHERE status = 'PREPARING' AND attempts >= ? AND (claimed_until IS NULL OR claimed_until <= ?)
            """.trimIndent(),
            Timestamp.from(now),
            maxAttempts,
            Timestamp.from(now),
        )

    /**
     * Takes [id] for a build until [until], counting the attempt, if it is free and has attempts
     * left. The attempt's number, or null. **[until] is the claim's token**: [ready], [release] and
     * [fail] act only while the row still holds it, so a build whose claim was taken from it (a
     * withdrawal, [restartFor]) can change nothing.
     */
    fun claim(
        id: UUID,
        now: Instant,
        until: Instant,
        maxAttempts: Int,
    ): Int? =
        jdbc
            .queryForList(
                """
                UPDATE data_exports SET attempts = attempts + 1, claimed_until = ?
                WHERE id = ? AND status = 'PREPARING' AND (claimed_until IS NULL OR claimed_until <= ?) AND attempts < ?
                RETURNING attempts
                """.trimIndent(),
                Int::class.java,
                Timestamp.from(until),
                id,
                Timestamp.from(now),
                maxAttempts,
            ).firstOrNull()

    /**
     * PREPARING to READY with its archive, by the build holding [claim]. False if it no longer holds
     * it: an account deletion removed the row, or a withdrawal restarted it.
     */
    @Suppress("LongParameterList") // The claim and the archive's four facts.
    fun ready(
        id: UUID,
        claim: Instant,
        key: String,
        sizeBytes: Long,
        readyAt: Instant,
        expiresAt: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE data_exports SET status = 'READY', storage_key = ?, size_bytes = ?, ready_at = ?, expires_at = ?, claimed_until = NULL
            WHERE id = ? AND status = 'PREPARING' AND claimed_until = ?
            """.trimIndent(),
            key,
            sizeBytes,
            Timestamp.from(readyAt),
            Timestamp.from(expiresAt),
            id,
            Timestamp.from(claim),
        ) == 1

    /** A failed attempt gives its claim back, so the next run may try again; only its own claim. */
    fun release(
        id: UUID,
        claim: Instant,
    ) {
        jdbc.update(
            "UPDATE data_exports SET claimed_until = NULL WHERE id = ? AND status = 'PREPARING' AND claimed_until = ?",
            id,
            Timestamp.from(claim),
        )
    }

    /** The last attempt failed: FAILED, if the build still holds [claim]. */
    fun fail(
        id: UUID,
        claim: Instant,
        at: Instant,
    ) {
        jdbc.update(
            "UPDATE data_exports SET status = 'FAILED', ended_at = ?, claimed_until = NULL " +
                "WHERE id = ? AND status = 'PREPARING' AND claimed_until = ?",
            Timestamp.from(at),
            id,
            Timestamp.from(claim),
        )
    }

    /**
     * A withdrawal in a bond [userIds] are in (ADR-0059 decision 12): every READY export of theirs
     * becomes EXPIRED, its key forgotten, and the keys are returned for their objects to be deleted;
     * every PREPARING one loses its claim and its attempts, so it is built again from what may be
     * read now, and a build in flight (which may have read the words before they were withdrawn)
     * can no longer make it READY.
     */
    fun invalidateFor(
        userIds: Collection<UUID>,
        at: Instant,
    ): List<String> {
        if (userIds.isEmpty()) return emptyList()
        val users = userIds.toTypedArray()
        restartFor(users)
        return jdbc
            .queryForList(
                """
                WITH stale AS (
                    SELECT id, storage_key FROM data_exports WHERE user_id = ANY (?) AND status = 'READY' FOR UPDATE
                )
                UPDATE data_exports d SET status = 'EXPIRED', storage_key = NULL, ended_at = ?
                FROM stale WHERE d.id = stale.id
                RETURNING stale.storage_key
                """.trimIndent(),
                String::class.java,
                users,
                Timestamp.from(at),
            ).filterNotNull()
    }

    private fun restartFor(users: Array<UUID>) {
        jdbc.update(
            "UPDATE data_exports SET claimed_until = NULL, attempts = 0 WHERE user_id = ANY (?) AND status = 'PREPARING'",
            users,
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

    /**
     * Every export of [userId], gone in one statement, and the archive keys the deleted rows held.
     * One statement, so that an export made READY by a build that committed while this waited on
     * its row is deleted with its key in hand, never left as an object nothing names.
     */
    fun deleteAllOf(userId: UUID): List<String> =
        jdbc
            .queryForList("DELETE FROM data_exports WHERE user_id = ? RETURNING storage_key", String::class.java, userId)
            .filterNotNull()

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
