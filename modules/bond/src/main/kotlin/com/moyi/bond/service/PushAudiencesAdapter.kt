package com.moyi.bond.service

import com.moyi.bond.api.DueReminder
import com.moyi.bond.api.PushAudience
import com.moyi.bond.api.PushAudienceMember
import com.moyi.bond.api.PushAudiences
import com.moyi.bond.infra.database.MemberNotifications
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * [PushAudiences] over the member rows (ADR-0046). Reads only, and decides
 * nothing about any one member: whether the bond takes writes is the bond's
 * own rule ([com.moyi.bond.domain.BondStatus.isOpen]), and each member's
 * settings travel with them to `notification`, which applies them to that
 * member's copy alone.
 */
@Service
internal class PushAudiencesAdapter(
    private val members: MemberNotifications,
    private val roster: ReminderRosterAdapter,
) : PushAudiences {
    @Transactional(readOnly = true)
    override fun audienceOf(bondId: UUID): PushAudience? {
        val rows = members.audienceOf(bondId) ?: return null
        return PushAudience(
            bondId = bondId,
            takesWrites = rows.status.isOpen,
            members = rows.members.map { PushAudienceMember(memberId = it.memberId, userId = it.userId, recipient = it.recipient()) },
        )
    }

    @Transactional(readOnly = true)
    override fun onThisDayDueBetween(
        localTime: LocalTime,
        after: Instant,
        upTo: Instant,
    ): List<DueReminder> =
        // The reminder's candidates are every member still in a bond that takes entries; of
        // those, only who opted in (FR-092). Nobody else's choice is read.
        roster.dueBetween(members.reminderCandidates().filter { it.preferences.onThisDay }, { localTime }, after, upTo)
}
