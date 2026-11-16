package com.moyi.notification.infra.database

import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.SharedChange
import com.moyi.notification.domain.NotificationStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The `notifications` ledger (V29, V30, V58, V68, V71). */
@Suppress("TooManyFunctions") // One per statement on the ledger, in the ledger's terms.
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
                                       zone, quiet_hours_start, quiet_hours_end, wanted, bond_date, nudge, leave_free_across_bonds, shared_change)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            push.bondDate?.let(java.sql.Date::valueOf),
            push.nudge,
            push.leaveFreeAcrossBonds,
            push.sharedChange?.name,
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
                       zone, quiet_hours_start, quiet_hours_end, wanted, bond_date, nudge, leave_free_across_bonds, shared_change
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
     * `CANCELLED` for every push under [dedupeKeys] that is still owed:
     * `REQUESTED` or `HELD`. One statement, through `notifications_dedupe_key_key`.
     * A push the consumer has locked is waited for, and is then either settled
     * (and left alone) or still owed (and cancelled): never both sent and
     * cancelled.
     *
     * @return what this call cancelled, and only that: a push already settled,
     *   cancelled before, or never asked for is not returned, so nothing is
     *   counted or inherited from twice (spec C16, ADR-0068 decision 7).
     */
    fun cancelOwed(dedupeKeys: Collection<String>): List<CancelledPush> =
        jdbc.query(
            """
            UPDATE notifications SET status = 'CANCELLED'
             WHERE dedupe_key = ANY (?) AND status IN ('REQUESTED', 'HELD')
            RETURNING category, nudge, leave_free_across_bonds
            """.trimIndent(),
            { row, _ -> cancelledPush(row) },
            dedupeKeys.toTypedArray(),
        )

    /**
     * `CANCELLED` for every push of [category] to [userId] in [bondId] still owed (`REQUESTED` or
     * `HELD`) but [except]: a coalescing push superseding its group (ADR-0069 decision 7). One
     * statement, served by `notifications_recipient_idx (user_id, category, created_at)`. A push
     * the consumer has locked is waited for, and its status read again: sent and left alone, or
     * still owed and cancelled, never both. Rows committed after this statement began are not
     * seen, so two requests at once may both survive (spec §7): at most one push too many, never
     * none.
     *
     * @return what this call cancelled, and only that.
     */
    fun cancelOwedInGroup(
        userId: UUID,
        bondId: UUID,
        category: PushCategory,
        except: UUID,
    ): List<CoalescedPush> =
        jdbc.query(
            """
            UPDATE notifications SET status = 'CANCELLED'
             WHERE user_id = ? AND bond_id = ? AND category = ? AND id <> ? AND status IN ('REQUESTED', 'HELD')
            RETURNING category, nudge, not_before, shared_change
            """.trimIndent(),
            { row, _ ->
                CoalescedPush(
                    category = PushCategory.valueOf(row.getString("category")),
                    nudge = row.getObject("nudge", Integer::class.java)?.toInt(),
                    notBefore = row.getTimestamp("not_before").toInstant(),
                    sharedChange = row.getString("shared_change")?.let(SharedChange::valueOf),
                )
            },
            userId,
            bondId,
            category.name,
            except,
        )

    /**
     * Sets when the push [id] may go and which change it carries: a coalescing push taking over the
     * earliest time, and the merged change, of what it superseded (ADR-0069 decision 7).
     */
    fun retime(
        id: UUID,
        notBefore: Instant,
        sharedChange: SharedChange?,
    ): Boolean =
        jdbc.update(
            "UPDATE notifications SET not_before = ?, shared_change = ? WHERE id = ?",
            timestamp(notBefore),
            sharedChange?.name,
            id,
        ) == 1

    /**
     * `CANCELLED` for every push of [bondId] still owed (`REQUESTED` or `HELD`), every member's and
     * every category's: a Bond that has ended (ADR-0069 decision 5). One statement over the Bond's
     * rows, at most a few hundred (spec §6.2). A push the consumer has locked is waited for, as in
     * [cancelOwed].
     *
     * @return what this call cancelled, and only that.
     */
    fun cancelOwedOfBond(bondId: UUID): List<CancelledPush> =
        jdbc.query(
            """
            UPDATE notifications SET status = 'CANCELLED'
             WHERE bond_id = ? AND status IN ('REQUESTED', 'HELD')
            RETURNING category, nudge, leave_free_across_bonds
            """.trimIndent(),
            { row, _ -> cancelledPush(row) },
            bondId,
        )

    /**
     * Lowers the leave-free of the push [id] to [leaveFree], never raises it: a superseding nudge
     * taking over the claim of what it cancelled (ADR-0068 decision 7).
     */
    fun lowerLeaveFree(
        id: UUID,
        leaveFree: Int,
    ): Boolean =
        jdbc.update(
            "UPDATE notifications SET leave_free_across_bonds = ? WHERE id = ? AND leave_free_across_bonds > ?",
            leaveFree,
            id,
            leaveFree,
        ) == 1

    /**
     * Whether a push of [category] to [userId] in [bondId], asked for after
     * [since], has one of [statuses]. Read by `notifications_recipient_idx`
     * (V31).
     */
    fun existsSince(
        userId: UUID,
        bondId: UUID,
        category: PushCategory,
        statuses: Set<NotificationStatus>,
        since: Instant,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM notifications
               WHERE user_id = ? AND category = ? AND created_at > ? AND bond_id = ?
                 AND status = ANY (?)
            )
            """.trimIndent(),
            Boolean::class.java,
            userId,
            category.name,
            timestamp(since),
            bondId,
            statuses.map { it.name }.toTypedArray(),
        ) == true

    /**
     * The Bond and Bond-day of each push to [userId] in one of [bondIds], of one of [categories],
     * in one of [statuses], asked for after [since]. One read through
     * `notifications_recipient_idx (user_id, category, created_at)`: the user and each category
     * are its equality columns and [since] bounds its range; Bond and status are filtered on the
     * rows it finds. A push about no day is not returned.
     */
    fun datedSince(
        userId: UUID,
        bondIds: Collection<UUID>,
        categories: Set<PushCategory>,
        statuses: Set<NotificationStatus>,
        since: Instant,
    ): List<DatedPush> =
        jdbc.query(
            DATED_SINCE,
            { row, _ -> DatedPush(row.getObject("bond_id", UUID::class.java), row.getDate("bond_date").toLocalDate()) },
            userId,
            categories.map { it.name }.toTypedArray(),
            timestamp(since),
            bondIds.toTypedArray(),
            statuses.map { it.name }.toTypedArray(),
        )

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

    private fun cancelledPush(row: ResultSet): CancelledPush =
        CancelledPush(
            category = PushCategory.valueOf(row.getString("category")),
            nudge = row.getObject("nudge", Integer::class.java)?.toInt(),
            leaveFreeAcrossBonds = row.getInt("leave_free_across_bonds"),
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
            bondDate = row.getDate("bond_date")?.toLocalDate(),
            nudge = row.getObject("nudge", Integer::class.java)?.toInt(),
            leaveFreeAcrossBonds = row.getInt("leave_free_across_bonds"),
            sharedChange = row.getString("shared_change")?.let(SharedChange::valueOf),
        )
    }

    private fun timestamp(instant: Instant): Timestamp = Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS))

    internal companion object {
        /** [datedSince]'s statement; a test EXPLAINs it to see that the recipient index can serve it. */
        val DATED_SINCE =
            """
            SELECT DISTINCT bond_id, bond_date FROM notifications
             WHERE user_id = ? AND category = ANY (?) AND created_at > ?
               AND bond_id = ANY (?) AND status = ANY (?) AND bond_date IS NOT NULL
            """.trimIndent()
    }
}

/** A push's Bond and the Bond-day it is about. */
internal data class DatedPush(
    val bondId: UUID,
    val bondDate: LocalDate,
)

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
    /** The Bond-day the push is about; `null` for a push about no day. */
    val bondDate: LocalDate?,
    /** Which nudge of its Bond-day a reminder is; `null` for any other push (V68). */
    val nudge: Int?,
    /** How many cross-Bond slots the push must leave free when sent (V68). */
    val leaveFreeAcrossBonds: Int,
    /** Which shared change a shared-setting push is about; `null` for any other push (V71). */
    val sharedChange: SharedChange?,
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
    /** The Bond-day the push is about: `null` for a push about no day, and for a row written before V58. */
    val bondDate: LocalDate?,
    /** Which nudge of its Bond-day a reminder is: `null` for any other push, and for a row written before V68. */
    val nudge: Int?,
    /** How many cross-Bond slots the push must leave free when sent: 0 for a row written before V68. */
    val leaveFreeAcrossBonds: Int,
    /** Which shared change a shared-setting push is about: `null` for any other push (V71). */
    val sharedChange: SharedChange?,
)

/**
 * A push [NotificationStore.cancelOwed] or [NotificationStore.cancelOwedOfBond] has just called
 * off: what a superseding nudge inherits from, and what the cancellation is counted by.
 */
internal data class CancelledPush(
    val category: PushCategory,
    /** Which nudge of its Bond-day it was: `null` for any other push, and for a row written before V68. */
    val nudge: Int?,
    /** How many cross-Bond slots it had to leave free when sent. */
    val leaveFreeAcrossBonds: Int,
)

/**
 * A push [NotificationStore.cancelOwedInGroup] has just called off: what the coalescing push that
 * superseded it takes over (its time and its change), and what the cancellation is counted by.
 */
internal data class CoalescedPush(
    val category: PushCategory,
    /** Always `null` for a coalescing category; kept so the cancellation is counted like any other. */
    val nudge: Int?,
    val notBefore: Instant,
    val sharedChange: SharedChange?,
)

/** Quiet hours as two columns hold them. */
internal data class QuietWindow(
    val start: LocalTime,
    val end: LocalTime,
)
