package com.moyi.notification.infra.database

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * What FR-082's budget reads of the `notifications` ledger, and the lock it
 * reads it under (ADR-0045). Apart from [NotificationStore], which writes the
 * rows: these only count them.
 */
@Component
internal class PushBudgetLedger(
    private val jdbc: JdbcTemplate,
) {
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

    private fun timestamp(instant: Instant): Timestamp = Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS))
}

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
