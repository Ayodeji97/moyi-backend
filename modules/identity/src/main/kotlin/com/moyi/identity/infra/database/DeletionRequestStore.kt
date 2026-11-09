package com.moyi.identity.infra.database

import com.moyi.identity.domain.DeletionRequest
import com.moyi.identity.domain.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `deletion_requests` (V52, ADR-0058). Plain SQL: a request is ids and instants, every write
 * is conditional on the row's state, and nothing here needs Hibernate's identity map, which
 * the erasure beside it bypasses anyway.
 *
 * Not transactional itself: the caller's transaction holds the locks taken here.
 */
@Component
internal class DeletionRequestStore(
    private val jdbc: JdbcTemplate,
) {
    /** The user's row, locked: what serialises two requests, a request and a cancel, and either with the job. */
    fun lockUser(userId: UserId) {
        jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID::class.java, userId.value)
    }

    /** The one pending request, if any: neither cancelled nor carried out. */
    fun findPending(userId: UserId): DeletionRequest? =
        jdbc
            .query(
                "SELECT id, user_id, requested_at FROM deletion_requests " +
                    "WHERE user_id = ? AND cancelled_at IS NULL AND executed_at IS NULL",
                ROW,
                userId.value,
            ).singleOrNull()

    fun insert(request: DeletionRequest) {
        jdbc.update(
            "INSERT INTO deletion_requests (id, user_id, requested_at, execute_after) VALUES (?, ?, ?, ?)",
            request.id,
            request.userId.value,
            Timestamp.from(request.requestedAt),
            Timestamp.from(request.cancellableUntil),
        )
    }

    fun cancel(
        request: DeletionRequest,
        at: Instant,
    ) {
        jdbc.update(
            "UPDATE deletion_requests SET cancelled_at = ? WHERE id = ? AND cancelled_at IS NULL AND executed_at IS NULL",
            Timestamp.from(at),
            request.id,
        )
    }

    /** Up to [limit] user ids whose pending request fell due by [now], oldest first. Unlocked: the caller locks each. */
    fun dueBy(
        now: Instant,
        limit: Int,
    ): List<UserId> =
        jdbc
            .queryForList(
                "SELECT user_id FROM deletion_requests WHERE cancelled_at IS NULL AND executed_at IS NULL AND execute_after <= ? " +
                    "ORDER BY execute_after, id LIMIT ?",
                UUID::class.java,
                Timestamp.from(now),
                limit,
            ).map { UserId(it!!) }

    fun markExecuted(
        request: DeletionRequest,
        at: Instant,
    ) {
        jdbc.update("UPDATE deletion_requests SET executed_at = ? WHERE id = ?", Timestamp.from(at), request.id)
    }

    private companion object {
        val ROW =
            RowMapper { rs, _ ->
                DeletionRequest(
                    id = rs.getObject("id", UUID::class.java),
                    userId = UserId(rs.getObject("user_id", UUID::class.java)),
                    requestedAt = rs.getTimestamp("requested_at").toInstant(),
                )
            }
    }
}
