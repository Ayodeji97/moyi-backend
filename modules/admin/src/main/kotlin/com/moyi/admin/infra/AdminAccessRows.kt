package com.moyi.admin.infra

import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.admin.domain.LookupPurpose
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * The audit rows about one member, for the data export's disclosure (A2 spec §9.3): every row
 * whose target is the member's account (`USER`) or one of their media (`MEDIA`), oldest first,
 * read through V60's `audit_log_target_idx (target_type, target_id, occurred_at)`. Which of them
 * the export shows is `Disclosure.shows`'s to say, not this query's: it reads every action on
 * either target, and hands each row's `target_type` on, so that the rule (which action on which
 * target) is written in one place.
 *
 * `target_id` is `text`, so the member's id and their media ids are bound as text: the media ids
 * as **one** `text[]` parameter (a `uuid[]` would not compare with `text`).
 *
 * **Never throws on what a row holds.** An action or outcome this code does not know (a row a
 * later version wrote), a `metadata.purpose` that is not a [LookupPurpose], or a
 * `metadata.reportDriven` that is not a JSON boolean, is read as null: unknown, and so withheld.
 * A malformed row must not fail every export of the member it is about.
 */
@Component
internal class AdminAccessRows(
    jdbcTemplate: JdbcTemplate,
) {
    private val jdbc = JdbcClient.create(jdbcTemplate)

    fun about(
        userId: UUID,
        mediaIds: List<UUID>,
    ): List<AccessRow> =
        jdbc
            .sql(
                """
                SELECT occurred_at, id, action, outcome, target_type, metadata ->> 'purpose' AS purpose,
                       (metadata -> 'reportDriven')::text AS report_driven
                FROM audit_log WHERE target_type = 'USER' AND target_id = :user
                UNION ALL
                SELECT occurred_at, id, action, outcome, target_type, metadata ->> 'purpose' AS purpose,
                       (metadata -> 'reportDriven')::text AS report_driven
                FROM audit_log WHERE target_type = 'MEDIA' AND target_id = ANY(:media)
                ORDER BY occurred_at, id
                """.trimIndent(),
            ).param("user", userId.toString())
            .param("media", mediaIds.map(UUID::toString).toTypedArray())
            .query(ROW)
            .list()

    /** One row as the disclosure reads it; a null field is one the row did not hold, or held malformed. */
    class AccessRow(
        val at: Instant,
        val action: AuditAction?,
        val outcome: AuditOutcome?,
        val targetType: String,
        val purpose: LookupPurpose?,
        val reportDriven: Boolean?,
    ) {
        override fun toString(): String = "AccessRow(at=$at, action=$action, outcome=$outcome, targetType=$targetType)"
    }

    private companion object {
        val ROW =
            RowMapper { rs, _ ->
                AccessRow(
                    at = rs.getTimestamp("occurred_at").toInstant(),
                    action = AuditAction.entries.firstOrNull { it.name == rs.getString("action") },
                    outcome = AuditOutcome.entries.firstOrNull { it.name == rs.getString("outcome") },
                    targetType = rs.getString("target_type"),
                    purpose = LookupPurpose.named(rs.getString("purpose")),
                    reportDriven = jsonBoolean(rs.getString("report_driven")),
                )
            }

        /** A JSON `true` or `false`; anything else (absent, a string, a number) is null. */
        fun jsonBoolean(raw: String?): Boolean? =
            when (raw) {
                "true" -> true
                "false" -> false
                else -> null
            }
    }
}
