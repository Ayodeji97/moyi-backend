package com.moyi.notification.infra.database

import com.moyi.notification.api.PushCategory
import com.moyi.notification.domain.NotificationStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The `notifications` ledger (V29). */
@Component
internal class NotificationStore(
    private val jdbc: JdbcTemplate,
) {
    /**
     * Records [push] unless a push with its dedupe key is already recorded.
     * `ON CONFLICT DO NOTHING` and not a read first: two transactions asking
     * at once both pass a read, and at READ COMMITTED the second insert waits
     * for the first to commit and then does nothing.
     *
     * @return whether this call made the row.
     */
    fun insertOnce(push: NewPush): Boolean =
        jdbc.update(
            """
            INSERT INTO notifications (id, user_id, bond_id, category, dedupe_key, not_before, stale_after, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
            ON CONFLICT (dedupe_key) DO NOTHING
            """.trimIndent(),
            push.id,
            push.userId,
            push.bondId,
            push.category.name,
            push.dedupeKey,
            timestamp(push.notBefore),
            timestamp(push.staleAfter),
            timestamp(push.createdAt),
        ) == 1

    /**
     * The push, locked until the transaction ends. The delivery's own row lock
     * already keeps two dispatchers from handling one delivery at once; this
     * keeps anything else that may one day settle a push (a sweep, a second
     * event for it) from settling it at the same moment.
     */
    fun lock(id: UUID): StoredPush? =
        jdbc
            .query(
                """
                SELECT id, user_id, bond_id, category, not_before, stale_after, status
                  FROM notifications WHERE id = ? FOR UPDATE
                """.trimIndent(),
                { row, _ ->
                    StoredPush(
                        id = row.getObject("id", UUID::class.java),
                        userId = row.getObject("user_id", UUID::class.java),
                        bondId = row.getObject("bond_id", UUID::class.java),
                        category = PushCategory.valueOf(row.getString("category")),
                        notBefore = row.getTimestamp("not_before").toInstant(),
                        staleAfter = row.getTimestamp("stale_after").toInstant(),
                        status = NotificationStatus.valueOf(row.getString("status")),
                    )
                },
                id,
            ).firstOrNull()

    /**
     * Moves a push out of `REQUESTED`, once. A push already settled is left as
     * it is and the call says so.
     */
    fun settle(
        id: UUID,
        status: NotificationStatus,
        now: Instant,
        providerMessageId: String? = null,
        errorClass: String? = null,
    ): Boolean {
        require(status != NotificationStatus.REQUESTED) { "A push is settled into a final status." }
        val at = timestamp(now)
        return jdbc.update(
            """
            UPDATE notifications
               SET status = ?, sent_at = ?, provider_message_id = ?, error_class = ?
             WHERE id = ? AND status = 'REQUESTED'
            """.trimIndent(),
            status.name,
            if (status == NotificationStatus.SENT) at else null,
            providerMessageId,
            errorClass,
            id,
        ) == 1
    }

    private fun timestamp(instant: Instant): Timestamp = Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS))
}

/** A push about to be recorded. */
internal data class NewPush(
    val id: UUID,
    val userId: UUID,
    val bondId: UUID,
    val category: PushCategory,
    val dedupeKey: String,
    val notBefore: Instant,
    val staleAfter: Instant,
    val createdAt: Instant,
)

/** A push as the ledger holds it, with what the consumer needs to send or settle it. */
internal data class StoredPush(
    val id: UUID,
    val userId: UUID,
    val bondId: UUID,
    val category: PushCategory,
    val notBefore: Instant,
    val staleAfter: Instant,
    val status: NotificationStatus,
)
