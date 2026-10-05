package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.common.web.NotFoundException
import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.infra.database.BondDayStore
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.gratitude.infra.database.redacted
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Resolves an entry's parent before locking; only its author may mutate it.
 * The initial read grants nothing: membership and author are checked, then
 * bond, day and entry are read under the locks, in C1 order. The day lock
 * serializes editing against reveal, including C3's bond-lock-free sweep.
 */
@Service
internal class ChangeEntry(
    private val access: BondAccess,
    private val days: BondDayStore,
    private val entries: EntryStore,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val joining: ReconcileJoiningDay,
) {
    fun change(
        userId: UUID,
        entryId: EntryId,
        replacement: EntryText?,
        unsupportedMedia: Boolean = false,
    ): EntryView =
        try {
            checkNotNull(
                transactions.execute {
                    val initial = entries.find(entryId) ?: throw NotFoundException("That entry was not found.")
                    val membership = access.lockMembershipOf(userId, initial.bondId)
                    if (initial.authorMemberId != membership.memberId) throw NotFoundException("That entry was not found.")
                    if (membership.hasLeft || !membership.isOpen) throw BondArchivedException()
                    if (unsupportedMedia) throw MediaNotYetSupportedException()
                    val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
                    val day = lockDays(membership, initial.bondDayId, now)
                    val entry = entries.lockAndFind(entryId)
                    if (replacement != null && !entry.isEditable) throw EntryImmutableException()
                    val changed = if (replacement == null) entry.erase(now) else entry.edit(replacement, now)
                    val nextDay = if (replacement == null && !entry.isErased) day.withoutEntry() else day
                    if (changed != entry) entries.update(changed)
                    if (nextDay != day) days.update(nextDay)
                    EntryView(changed.readBy(membership.asReader()), nextDay)
                },
            )
        } catch (violation: DataIntegrityViolationException) {
            throw violation.redacted()
        }

    /** Acquire any two days chronologically before the entry lock. */
    private fun lockDays(
        membership: BondMembership,
        dayId: BondDayId,
        now: Instant,
    ): BondDay {
        val initial = checkNotNull(days.find(dayId))
        val joiningDate = joining.window(membership)?.date
        if (joiningDate != null && !joiningDate.isAfter(initial.date)) joining.underBondLock(membership, now)
        val day = days.lockAndFind(dayId)
        if (joiningDate != null && joiningDate.isAfter(day.date)) joining.underBondLock(membership, now)
        return day
    }
}
