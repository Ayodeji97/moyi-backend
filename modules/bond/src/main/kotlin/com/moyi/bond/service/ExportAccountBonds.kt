package com.moyi.bond.service

import com.moyi.bond.api.BondExports
import com.moyi.bond.api.ExportedBond
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.database.AccountBondFacts
import com.moyi.bond.infra.database.BondStore
import com.moyi.identity.api.UserDirectory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [BondExports]: the bonds `GET /bonds` would list for the person, ended and left ones included,
 * each with the person's own member row and the partner's name by [displayNameOf], the one rule
 * the bond routes print it by.
 */
@Service
internal class ExportAccountBonds(
    private val bonds: BondStore,
    private val users: UserDirectory,
    private val facts: AccountBondFacts,
) : BondExports {
    @Transactional(readOnly = true)
    override fun bondsOf(userId: UUID): List<ExportedBond> {
        val all = bonds.findAllByMember(UserId(userId))
        if (all.isEmpty()) return emptyList()
        val names = users.findAll(all.flatMap { bond -> bond.members.map { it.userId.value } }.distinct())
        val blocked = facts.blocksMadeBy(userId)
        val mine = all.mapNotNull { it.memberOf(UserId(userId)) }
        val withdrawn = facts.withdrawalsOf(mine.map { it.id.value })
        return all.mapNotNull { bond ->
            val me = bond.memberOf(UserId(userId)) ?: return@mapNotNull null
            val partner = bond.members.firstOrNull { it.userId != me.userId }
            ExportedBond(
                id = bond.id.value,
                name = bond.name,
                type = bond.type.name,
                status = bond.status.name,
                createdAt = bond.createdAt,
                archivedAt = bond.archivedAt,
                anchorTimezone = bond.anchorTimezone.id,
                revealTimeLocal = bond.revealTimeLocal,
                strictMode = bond.strictMode,
                semanticSearchEnabled = bond.semanticSearchEnabled,
                role = me.role.name,
                joinedAt = me.joinedAt,
                leftAt = me.leftAt,
                reminderTimeLocal = me.reminderTimeLocal,
                reminderTimezone = me.reminderTimezone.id,
                quietHoursStart = me.quietHoursStart,
                quietHoursEnd = me.quietHoursEnd,
                nicknameForOther = me.nicknameForOther,
                partnerDisplayName = partner?.let { displayNameOf(names[it.userId.value]) },
                blockedAt = blocked[bond.id.value],
                withdrewEntriesAt = withdrawn[me.id.value],
            )
        }
    }
}
