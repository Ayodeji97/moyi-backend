package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayWindow
import com.moyi.gratitude.infra.database.BondDayStore
import com.moyi.gratitude.infra.database.redacted
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * C1 may have left the activation day's entries suspended for days before
 * deployment. Locate that day from the timeline, not today's date. Ordinary
 * reads remain unlocked; only a suspended activation row needs reconciliation.
 * No missing day is manufactured here, and no earlier private day is touched.
 */
@Service
internal class ReconcileJoiningDay(
    private val access: BondAccess,
    private val days: BondDayStore,
    private val reveal: RevealDay,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) {
    fun window(membership: BondMembership): DayWindow? = membership.activeSince?.let { membership.anchorTimeline.asCalendar().dayAt(it) }

    /** Called before a read transaction, so its conditional write is not read-only. */
    fun beforeRead(membership: BondMembership) {
        val joining = window(membership) ?: return
        if (days.findByBondAndDate(membership.bondId, joining.date)?.status != BondDayStatus.SUSPENDED) return
        try {
            transactions.executeWithoutResult {
                val fresh = access.lockMembershipOf(membership.userId, membership.bondId)
                underBondLock(fresh, clock.instant().truncatedTo(ChronoUnit.MICROS))
            }
        } catch (violation: DataIntegrityViolationException) {
            throw violation.redacted()
        }
    }

    /** Writers call in chronological day order, after taking the bond lock. */
    fun underBondLock(
        membership: BondMembership,
        now: Instant,
    ) {
        val joining = window(membership) ?: return
        val initial =
            days
                .findByBondAndDate(membership.bondId, joining.date)
                ?.takeIf { it.status == BondDayStatus.SUSPENDED } ?: return
        val locked = days.lockAndFind(initial.id)
        val resumed = locked.extendedTo(joining).resumeJoiningDay(membership.activeSince)
        if (resumed != locked) reveal.apply(resumed, membership.revealTimeLocal, now)
    }
}
