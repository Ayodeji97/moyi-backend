package com.moyi.notification.infra.database

import com.moyi.notification.api.PushCategory
import com.moyi.notification.domain.NotificationStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The `notifications` ledger (V29, V30). */
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
            INSERT INTO notifications (id, user_id, bond_id, category, dedupe_key, not_before, stale_after, status, created_at,
                                       zone, quiet_hours_start, quiet_hours_end, wanted)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?)
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
            push.zone.id,
            push.quietHoursStart?.let(Time::valueOf),
            push.quietHoursEnd?.let(Time::valueOf),
            push.wanted,
        ) == 1

    /**
     * The push, locked until the transaction ends. The delivery's own row lock
     * already keeps two dispatchers from handling one delivery at once; this
     * keeps anything else that may settle a push (the release of held pushes,
     * a second event for it) from settling it at the same moment.
     */
    fun lock(id: UUID): StoredPush? =
        jdbc
            .query(
                """
                SELECT id, user_id, bond_id, category, not_before, stale_after, status,
                       zone, quiet_hours_start, quiet_hours_end, wanted
                  FROM notifications WHERE id = ? FOR UPDATE
                """.trimIndent(),
                { row, _ -> storedPush(row) },
                id,
            ).firstOrNull()

    /**
     * Moves a push out of `REQUESTED`, once. A push already settled is left as
     * it is and the call says so.
     */
    @Suppress("LongParameterList") // A row's settlement, column for column.
    fun settle(
        id: UUID,
        status: NotificationStatus,
        now: Instant,
        providerMessageId: String? = null,
        errorClass: String? = null,
        reserved: Boolean = false,
    ): Boolean {
        require(status != NotificationStatus.REQUESTED && status != NotificationStatus.HELD) { "A push is settled into a final status." }
        require(!reserved || status == NotificationStatus.SENT) { "Only a sent push took a slot." }
        val at = timestamp(now)
        return jdbc.update(
            """
            UPDATE notifications
               SET status = ?, sent_at = ?, provider_message_id = ?, error_class = ?, reserved = ?
             WHERE id = ? AND status = 'REQUESTED'
            """.trimIndent(),
            status.name,
            if (status == NotificationStatus.SENT) at else null,
            providerMessageId,
            errorClass,
            reserved,
            id,
        ) == 1
    }

    /**
     * Holds a `REQUESTED` push back until [until]: quiet hours (ADR-0045).
     * The push stays owed, and [releaseDue] hands it back when the time comes.
     */
    fun hold(
        id: UUID,
        until: Instant,
    ): Boolean =
        jdbc.update(
            "UPDATE notifications SET status = 'HELD', not_before = ? WHERE id = ? AND status = 'REQUESTED'",
            timestamp(until),
            id,
        ) == 1

    /**
     * Puts held pushes whose hold has ended back to `REQUESTED`, at most
     * [limit], earliest first, and returns their ids for the caller to
     * publish. `SKIP LOCKED`: a second instance releasing at the same moment
     * passes over what this one holds, and a push the consumer has locked is
     * not taken from under it.
     */
    fun releaseDue(
        now: Instant,
        limit: Int,
    ): List<UUID> =
        jdbc
            .queryForList(
                """
                UPDATE notifications SET status = 'REQUESTED'
                 WHERE id IN (
                       SELECT id FROM notifications
                        WHERE status = 'HELD' AND not_before <= ?
                        ORDER BY not_before
                        LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING id
                """.trimIndent(),
                UUID::class.java,
                timestamp(now),
                limit,
            ).filterNotNull()

    /**
     * Serialises every budget decision for [userId] until the transaction
     * ends (FR-082, ADR-0045): two pushes for one member decided at once would
     * both count the same slot free. Transaction-scoped, so the commit or the
     * rollback lets it go. Namespace [BUDGET_LOCK_NAMESPACE] of the two-key form.
     */
    fun lockBudgetOf(userId: UUID) {
        jdbc.queryForObject(
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(?, hashtext(CAST(? AS text)))) AS held",
            Int::class.java,
            BUDGET_LOCK_NAMESPACE,
            userId,
        )
    }

    /** Pushes of [bondId] sent to [userId] in `[from, to)`, and whether one of them took the reserved slot. */
    fun sentInBond(
        userId: UUID,
        bondId: UUID,
        from: Instant,
        to: Instant,
    ): SentInBond =
        checkNotNull(
            jdbc.queryForObject(
                """
                SELECT count(*) AS sent, coalesce(bool_or(reserved), false) AS reserved
                  FROM notifications
                 WHERE status = 'SENT' AND user_id = ? AND bond_id = ? AND sent_at >= ? AND sent_at < ?
                """.trimIndent(),
                { row, _ -> SentInBond(row.getInt("sent"), row.getBoolean("reserved")) },
                userId,
                bondId,
                timestamp(from),
                timestamp(to),
            ),
        )

    /** Pushes sent to [userId] after [since], in every Bond. */
    fun sentSince(
        userId: UUID,
        since: Instant,
    ): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE status = 'SENT' AND user_id = ? AND sent_at > ?",
                Int::class.java,
                userId,
                timestamp(since),
            ),
        )

    private fun storedPush(row: ResultSet): StoredPush {
        val quietStart = row.getTime("quiet_hours_start")?.toLocalTime()
        val quietEnd = row.getTime("quiet_hours_end")?.toLocalTime()
        return StoredPush(
            id = row.getObject("id", UUID::class.java),
            userId = row.getObject("user_id", UUID::class.java),
            bondId = row.getObject("bond_id", UUID::class.java),
            category = PushCategory.valueOf(row.getString("category")),
            notBefore = row.getTimestamp("not_before").toInstant(),
            staleAfter = row.getTimestamp("stale_after").toInstant(),
            status = NotificationStatus.valueOf(row.getString("status")),
            zone = ZoneId.of(row.getString("zone")),
            quietHours = if (quietStart != null && quietEnd != null) QuietWindow(quietStart, quietEnd) else null,
            wanted = row.getBoolean("wanted"),
        )
    }

    private fun timestamp(instant: Instant): Timestamp = Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS))
}

/** A push about to be recorded, with the member's settings as they stand (ADR-0045). */
internal data class NewPush(
    val id: UUID,
    val userId: UUID,
    val bondId: UUID,
    val category: PushCategory,
    val dedupeKey: String,
    val notBefore: Instant,
    val staleAfter: Instant,
    val createdAt: Instant,
    val zone: ZoneId,
    val quietHours: QuietWindow?,
    val wanted: Boolean,
) {
    val quietHoursStart: LocalTime? get() = quietHours?.start
    val quietHoursEnd: LocalTime? get() = quietHours?.end
}

/** A push as the ledger holds it, with what the consumer needs to decide, send or settle it. */
internal data class StoredPush(
    val id: UUID,
    val userId: UUID,
    val bondId: UUID,
    val category: PushCategory,
    val notBefore: Instant,
    val staleAfter: Instant,
    val status: NotificationStatus,
    /** The member's reminder zone in this Bond, as it was when the push was asked for. */
    val zone: ZoneId,
    val quietHours: QuietWindow?,
    /** Whether the member wanted this category when the push was asked for. */
    val wanted: Boolean,
)

/** Quiet hours as two columns hold them. */
internal data class QuietWindow(
    val start: LocalTime,
    val end: LocalTime,
)

/** One Bond's spend of a member's day (FR-082). */
internal data class SentInBond(
    val sent: Int,
    val reservedSpent: Boolean,
)

/**
 * The two-key advisory lock namespace of the push budget. Already taken
 * across the application: `1` identity's sessions lock (ADR-0021), `2` bond's
 * "bonds of a user" lock (FR-025) **and** N1's push-token lock, `3` the
 * idempotency keys (ADR-0031).
 */
internal const val BUDGET_LOCK_NAMESPACE = 4
