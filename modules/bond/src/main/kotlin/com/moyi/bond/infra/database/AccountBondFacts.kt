package com.moyi.bond.infra.database

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Two facts about a person's own acts in their bonds, for their data export (ADR-0059): the blocks
 * they made, and the withdrawals they made. Never the blocks made against them: whether a partner
 * blocked or left is not the person's to learn (ADR-0028).
 */
@Component
internal class AccountBondFacts(
    private val jdbc: JdbcTemplate,
) {
    /** When [userId] blocked somebody in each bond, by bond id. */
    fun blocksMadeBy(userId: UUID): Map<UUID, Instant> =
        jdbc
            .query(
                "SELECT bond_id, min(created_at) AS at FROM blocks WHERE blocker_user_id = ? GROUP BY bond_id",
                { rs, _ -> rs.getObject("bond_id", UUID::class.java) to rs.getTimestamp("at").toInstant() },
                userId,
            ).toMap()

    /** When each of [memberIds] withdrew their entries, by member id. */
    fun withdrawalsOf(memberIds: Collection<UUID>): Map<UUID, Instant> {
        if (memberIds.isEmpty()) return emptyMap()
        return jdbc
            .query(
                "SELECT member_id, withdrawn_at FROM bond_entry_withdrawals WHERE member_id = ANY (?)",
                { rs, _ -> rs.getObject("member_id", UUID::class.java) to rs.getTimestamp("withdrawn_at").toInstant() },
                memberIds.toTypedArray(),
            ).toMap()
    }
}
