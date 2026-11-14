package com.moyi.identity.infra.database

import com.moyi.identity.api.SuspensionSpan
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.UserStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `account_suspensions` (V65) and the two columns of `users` a suspension changes. Plain SQL,
 * as `AccountErasure` and `DeletionRequestStore` beside it: every write here is one targeted
 * statement under the user's row lock, and none of it needs Hibernate's identity map.
 *
 * Not transactional itself: the caller's transaction holds the locks taken here.
 */
@Component
internal class AccountSuspensionStore(
    private val jdbc: JdbcTemplate,
) {
    /** The user's row, locked, and what an act needs of it; `null` when no account has the id. */
    fun lockStanding(userId: UserId): Standing? =
        jdbc
            .query(
                "SELECT status, email_verified_at FROM users WHERE id = ? FOR UPDATE",
                { rs, _ -> Standing(UserStatus.valueOf(rs.getString("status")), rs.getTimestamp("email_verified_at") != null) },
                userId.value,
            ).singleOrNull()

    fun markSuspended(
        userId: UserId,
        at: Instant,
        invalidBefore: Instant,
    ) {
        jdbc.update(
            "UPDATE users SET status = 'SUSPENDED', tokens_invalid_before = ?, updated_at = ? WHERE id = ?",
            Timestamp.from(invalidBefore),
            Timestamp.from(at),
            userId.value,
        )
    }

    fun markLifted(
        userId: UserId,
        status: UserStatus,
        at: Instant,
    ) {
        jdbc.update("UPDATE users SET status = ?, updated_at = ? WHERE id = ?", status.name, Timestamp.from(at), userId.value)
    }

    fun openSpan(
        id: UUID,
        userId: UserId,
        at: Instant,
    ) {
        jdbc.update(
            "INSERT INTO account_suspensions (id, user_id, suspended_at) VALUES (?, ?, ?)",
            id,
            userId.value,
            Timestamp.from(at),
        )
    }

    /** @return how many spans it closed: one, when the invariant holds. */
    fun closeOpenSpan(
        userId: UserId,
        at: Instant,
    ): Int =
        jdbc.update(
            "UPDATE account_suspensions SET lifted_at = ? WHERE user_id = ? AND lifted_at IS NULL",
            Timestamp.from(at),
            userId.value,
        )

    fun openSpanSince(userId: UserId): Instant? =
        jdbc
            .query(
                "SELECT suspended_at FROM account_suspensions WHERE user_id = ? AND lifted_at IS NULL",
                { rs, _ -> rs.getTimestamp("suspended_at").toInstant() },
                userId.value,
            ).singleOrNull()

    fun openAmong(ids: Collection<UUID>): Set<UUID> =
        jdbc
            .query(
                "SELECT user_id FROM account_suspensions WHERE user_id = ANY (?) AND lifted_at IS NULL",
                idsOf(ids),
                RowMapper { rs, _ -> rs.getObject("user_id", UUID::class.java) },
            ).toSet()

    fun spansOf(ids: Collection<UUID>): List<SuspensionSpan> =
        jdbc.query(
            "SELECT user_id, suspended_at, lifted_at FROM account_suspensions WHERE user_id = ANY (?) ORDER BY user_id, suspended_at",
            idsOf(ids),
            RowMapper { rs, _ ->
                SuspensionSpan(
                    userId = rs.getObject("user_id", UUID::class.java),
                    from = rs.getTimestamp("suspended_at").toInstant(),
                    to = rs.getTimestamp("lifted_at")?.toInstant(),
                )
            },
        )

    /** One array parameter rather than an `IN` list: one statement shape, whatever the count. */
    private fun idsOf(ids: Collection<UUID>) =
        PreparedStatementSetter { statement ->
            statement.setArray(1, statement.connection.createArrayOf("uuid", ids.distinct().toTypedArray()))
        }
}

/** What an act reads of an account under its row lock. */
internal data class Standing(
    val status: UserStatus,
    val emailVerified: Boolean,
)
