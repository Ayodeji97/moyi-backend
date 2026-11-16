package com.moyi.identity.infra.database

import com.moyi.identity.domain.Email
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * An account as the database holds it now, for a caller that read it before taking the
 * sessions lock and must decide under it (`RequestVerification`, ADR-0066 amended 2026-11-14).
 * Plain SQL, past Hibernate's identity map, which would hand back the row as this transaction
 * first read it: an erasure or a suspension committed since would not be seen.
 */
@Component
internal class CurrentAccounts(
    private val users: UserRepository,
    private val jdbc: JdbcTemplate,
) {
    /**
     * [user] with its status, address, name, locale and verification read again; `null` when no
     * row has the id. Flushes first, so an account this transaction has just inserted
     * (registration) is found. Today the sessions lock's native query has already flushed by then
     * (Hibernate flushes before a native query), so registration passes without it; the flush is
     * here so that this read does not depend on what was called before it.
     */
    fun reread(user: User): User? {
        users.flush()
        return jdbc
            .query(
                "SELECT status, email, display_name, locale, email_verified_at, deleted_at FROM users WHERE id = ?",
                { rs, _ ->
                    user.copy(
                        status = UserStatus.valueOf(rs.getString("status")),
                        email = Email(rs.getString("email")),
                        displayName = rs.getString("display_name"),
                        locale = rs.getString("locale"),
                        emailVerifiedAt = rs.getTimestamp("email_verified_at")?.toInstant(),
                        deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
                    )
                },
                user.id.value,
            ).singleOrNull()
    }
}
