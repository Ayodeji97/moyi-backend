package com.moyi.identity.infra.database

import com.moyi.identity.domain.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

/**
 * What identity holds of a person, erased (doc 07 §7, ADR-0058 decision 6). Plain SQL in the
 * caller's transaction, which holds the user's row lock.
 *
 * **The row stays and is anonymised; it does not cascade** (doc 07 §7): other modules hold its
 * id, and `bond_members.user_id` must go on naming *a* row. The address becomes
 * `deleted-<id>@deleted.invalid`: no part of the old one, not a hash of it (a hash of an
 * address is the address to anyone with a list of addresses, ADR-0058 decision 6), unique
 * because the id is, and still the shape `Email` reads back. The old address is free to be
 * registered again. The name becomes a placeholder nobody sees: `UserDirectory` answers
 * nothing for a deleted account.
 *
 * Everything else identity keeps under the id goes: the password, every refresh token and
 * device (so every session), every verification token and code, and the consent records.
 */
@Component
internal class AccountErasure(
    private val jdbc: JdbcTemplate,
) {
    fun erase(
        userId: UserId,
        at: Instant,
    ) {
        val id = userId.value
        val now = Timestamp.from(at)
        jdbc.update(
            """
            UPDATE users
               SET email = ?, email_verified_at = NULL, display_name = ?, avatar_media_id = NULL,
                   status = 'DELETED', deleted_at = ?, updated_at = ?, tokens_invalid_before = ?
             WHERE id = ?
            """.trimIndent(),
            "deleted-$id@deleted.invalid",
            DELETED_NAME,
            now,
            now,
            now,
            id,
        )
        // Refresh tokens before devices: a token points at its device (ON DELETE SET NULL).
        for (table in OWNED_TABLES) jdbc.update("DELETE FROM $table WHERE user_id = ?", id)
    }

    private companion object {
        /** Shown to nobody: the directory answers `null` for a deleted account, and the bond's views say "Former member". */
        const val DELETED_NAME = "Deleted account"

        val OWNED_TABLES = listOf("credentials", "refresh_tokens", "devices", "verification_tokens", "consent_records")
    }
}
