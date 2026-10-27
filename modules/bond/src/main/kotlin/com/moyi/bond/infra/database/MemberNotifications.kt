package com.moyi.bond.infra.database

import com.moyi.notification.api.NotificationPreferences
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.LocalTime
import java.util.UUID

/**
 * `bond_members.notification_prefs` (V9), mapped for the first time (spec D-2,
 * ADR-0045), and the roster the daily reminder walks.
 *
 * **Plain JDBC, beside the JPA entity rather than on it.** The column is
 * `jsonb`, and mapping it onto `BondMemberEntity` would put a JSON type
 * through Hibernate for one column that only this class reads or writes. The
 * entity leaves the column unmapped, so a settings save never touches it, and
 * this class never touches anything else.
 *
 * **Stored as the member chose, read with the defaults filling the gaps.** V9
 * filled every row with `{}`, so a member who never chose has no keys, and a
 * key a later slice adds is absent from every row written before it. A
 * missing key is the default, and the default is notification's
 * ([NotificationPreferences.DEFAULTS]): nothing here restates it.
 */
@Component
internal class MemberNotifications(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    /** One member's preferences; `null` if there is no such member row. */
    fun of(memberId: UUID): NotificationPreferences? =
        jdbc
            .query(
                "SELECT notification_prefs::text AS prefs FROM bond_members WHERE id = ?",
                { row, _ -> read(row.getString("prefs")) },
                memberId,
            ).firstOrNull()

    /** Replaces one member's preferences, all six written out. */
    fun replace(
        memberId: UUID,
        preferences: NotificationPreferences,
    ) {
        val written =
            jdbc.update(
                "UPDATE bond_members SET notification_prefs = CAST(? AS jsonb) WHERE id = ?",
                mapper.writeValueAsString(write(preferences)),
                memberId,
            )
        check(written == 1) { "cannot update a member row that does not exist" }
    }

    /**
     * Every member who could be reminded: still in a bond that takes entries
     * (`PENDING_MEMBER` or `ACTIVE`), with what a reminder needs to know of
     * them. Whose reminder falls when is the caller's to work out
     * (`ReminderTime`), because a zone's day is not a SQL predicate.
     *
     * The whole table's active members, at most three bonds a person: a few
     * thousand rows at the scale doc 07:382 plans for, read every five
     * minutes. Revisit with a SQL prefilter on the offset if that changes.
     */
    fun reminderCandidates(): List<ReminderCandidate> =
        jdbc.query(
            """
            SELECT m.id, m.bond_id, m.user_id, m.reminder_time_local, m.reminder_timezone,
                   m.quiet_hours_start, m.quiet_hours_end, m.notification_prefs::text AS prefs
              FROM bond_members m
              JOIN bonds b ON b.id = m.bond_id
             WHERE m.left_at IS NULL AND b.status IN ('PENDING_MEMBER', 'ACTIVE')
             ORDER BY m.id
            """.trimIndent(),
        ) { row, _ -> candidate(row) }

    private fun candidate(row: ResultSet) =
        ReminderCandidate(
            memberId = row.getObject("id", UUID::class.java),
            bondId = row.getObject("bond_id", UUID::class.java),
            userId = row.getObject("user_id", UUID::class.java),
            reminderTimeLocal = row.getTime("reminder_time_local").toLocalTime(),
            reminderTimezone = row.getString("reminder_timezone"),
            quietHoursStart = row.getTime("quiet_hours_start")?.toLocalTime(),
            quietHoursEnd = row.getTime("quiet_hours_end")?.toLocalTime(),
            preferences = read(row.getString("prefs")),
        )

    private fun read(json: String): NotificationPreferences {
        val node = mapper.readTree(json)
        val defaults = NotificationPreferences.DEFAULTS
        return NotificationPreferences(
            reminder = node.flag(REMINDER) ?: defaults.reminder,
            partnerJoined = node.flag(PARTNER_JOINED) ?: defaults.partnerJoined,
            reveal = node.flag(REVEAL) ?: defaults.reveal,
            milestone = node.flag(MILESTONE) ?: defaults.milestone,
            onThisDay = node.flag(ON_THIS_DAY) ?: defaults.onThisDay,
            proposal = node.flag(PROPOSAL) ?: defaults.proposal,
        )
    }

    private fun write(preferences: NotificationPreferences): Map<String, Boolean> =
        linkedMapOf(
            REMINDER to preferences.reminder,
            PARTNER_JOINED to preferences.partnerJoined,
            REVEAL to preferences.reveal,
            MILESTONE to preferences.milestone,
            ON_THIS_DAY to preferences.onThisDay,
            PROPOSAL to preferences.proposal,
        )

    /** A stored yes or no; anything else, or nothing, is no answer. */
    private fun JsonNode.flag(name: String): Boolean? = get(name)?.takeIf { it.isBoolean }?.booleanValue()

    private companion object {
        // The stored keys are the API's names, so the column reads as the resource does.
        const val REMINDER = "reminder"
        const val PARTNER_JOINED = "partnerJoined"
        const val REVEAL = "reveal"
        const val MILESTONE = "milestone"
        const val ON_THIS_DAY = "onThisDay"
        const val PROPOSAL = "proposal"
    }
}

/** A member the daily reminder may be owed to, with their settings in that bond. */
internal data class ReminderCandidate(
    val memberId: UUID,
    val bondId: UUID,
    val userId: UUID,
    val reminderTimeLocal: LocalTime,
    val reminderTimezone: String,
    val quietHoursStart: LocalTime?,
    val quietHoursEnd: LocalTime?,
    val preferences: NotificationPreferences,
) {
    /** Ids only: when somebody sleeps is personal data (doc 18 §5). */
    override fun toString(): String = "ReminderCandidate(memberId=$memberId, bondId=$bondId)"
}
