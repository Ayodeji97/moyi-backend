package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.MemberId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `bond_entry_withdrawals` (V21): which members of a bond have taken their
 * own entries back (FR-029a). Written by the ending that asked for it, in
 * that ending's transaction; read whenever another module resolves a
 * membership. Plain SQL on a table nothing else maps, as [WritePauses].
 *
 * Insert-only. A withdrawal is not undone: the entries it names are erased.
 */
@Component
internal class EntryWithdrawals(
    private val jdbc: JdbcTemplate,
) {
    /**
     * Records that [memberId] withdrew their entries in [bondId], and answers
     * whether this call is the one that recorded it. The caller holds the
     * bond's lock and passes an instant already cut to microseconds.
     *
     * `false` means the member had withdrawn before: the row, and the
     * instant on it, are left as they were.
     */
    fun insertIfAbsent(
        bondId: BondId,
        memberId: MemberId,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            INSERT INTO bond_entry_withdrawals (bond_id, member_id, withdrawn_at) VALUES (?, ?, ?)
            ON CONFLICT (bond_id, member_id) DO NOTHING
            """.trimIndent(),
            bondId.value,
            memberId.value,
            Timestamp.from(at),
        ) == 1

    /** The members of [bondId] who have withdrawn. One read of the primary key's leading column. */
    fun membersOf(bondId: BondId): Set<UUID> =
        jdbc
            .queryForList("SELECT member_id FROM bond_entry_withdrawals WHERE bond_id = ?", UUID::class.java, bondId.value)
            .filterNotNull()
            .toSet()
}
