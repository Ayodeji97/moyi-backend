package com.moyi.notification.infra.database

import com.moyi.notification.api.PushPlatform
import com.moyi.notification.api.PushToken
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The `push_tokens` table (V29). Plain JDBC: each statement is an upsert or a
 * delete that a JPA entity would only wrap in a native query.
 *
 * No statement here logs, and the token is bound as a parameter, never written
 * into a statement's text: `JdbcTemplate` prints a statement's text at DEBUG,
 * and the logger that prints bound values is pinned off (application.yml).
 */
@Component
internal class PushTokenStore(
    private val jdbc: JdbcTemplate,
) {
    /**
     * Takes [token] from every device but [deviceId] and answers which of
     * them were [userId]'s. One statement, so the rows it reports are exactly
     * the rows it removed.
     */
    fun takeFromOthers(
        token: PushToken,
        deviceId: UUID,
        userId: UUID,
    ): Set<UUID> =
        jdbc
            .queryForList(
                """
                WITH taken AS (DELETE FROM push_tokens WHERE token = ? AND device_id <> ? RETURNING device_id, user_id)
                SELECT device_id FROM taken WHERE user_id = ?
                """.trimIndent(),
                UUID::class.java,
                token.value,
                deviceId,
                userId,
            ).filterNotNull()
            .toSet()

    /** The device's token is [token] from now on, whatever it held before. */
    fun put(
        deviceId: UUID,
        userId: UUID,
        platform: PushPlatform,
        token: PushToken,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO push_tokens (device_id, user_id, platform, token, updated_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (device_id) DO UPDATE
               SET token = EXCLUDED.token, platform = EXCLUDED.platform, updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            deviceId,
            userId,
            platform.name,
            token.value,
            Timestamp.from(now.truncatedTo(ChronoUnit.MICROS)),
        )
    }

    fun deleteOf(deviceId: UUID): Int = jdbc.update("DELETE FROM push_tokens WHERE device_id = ?", deviceId)

    fun deleteAllOf(userId: UUID): Int = jdbc.update("DELETE FROM push_tokens WHERE user_id = ?", userId)

    /**
     * Forgets a token the provider called invalid, **only if the device still
     * holds that token**: one registered since the send was made is a new,
     * good token and stays.
     */
    fun deleteIfStillHeld(held: HeldToken): Int =
        jdbc.update("DELETE FROM push_tokens WHERE device_id = ? AND token = ?", held.deviceId, held.token.value)

    /** Every token [userId]'s phones hold, in a fixed order so that a send is repeatable. */
    fun heldBy(userId: UUID): List<HeldToken> =
        jdbc.query(
            "SELECT device_id, token FROM push_tokens WHERE user_id = ? ORDER BY device_id",
            { row, _ -> HeldToken(row.getObject("device_id", UUID::class.java), PushToken(row.getString("token"))) },
            userId,
        )
}

/** One phone's token. [token] redacts itself, so this prints safely. */
internal data class HeldToken(
    val deviceId: UUID,
    val token: PushToken,
)
