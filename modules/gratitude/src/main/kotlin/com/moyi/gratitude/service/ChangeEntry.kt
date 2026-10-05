package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.common.web.NotFoundException
import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.Entry
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
    ): EntryView {
        reconcileJoiningDay(userId, entryId)
        return changeUnderLocks(userId, entryId, replacement, unsupportedMedia)
    }

    private fun changeUnderLocks(
        userId: UUID,
        entryId: EntryId,
        replacement: EntryText?,
        unsupportedMedia: Boolean,
    ): EntryView =
        try {
            checkNotNull(
                transactions.execute {
                    val (initial, membership) = authorOf(userId, entryId, lock = true)
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

    /**
     * Spec §12.4's "first gratitude operation", committed on its own before
     * the change it precedes. Run inside the change's transaction, a reveal
     * the reconcile had just made was rolled back whenever the change was
     * then refused — and on a day C1 left `SUSPENDED` with both entries, the
     * reveal is exactly what makes a `PATCH` a `409`. [PatchEntry] calls this
     * before it opens the key's transaction, for the same reason.
     *
     * It decides nothing: a caller this is not for is simply not reconciled
     * for, and [change] gives them its own answer.
     */
    fun reconcileJoiningDay(
        userId: UUID,
        entryId: EntryId,
    ) {
        authorOrNull(userId, entryId, lock = false)?.let { (_, membership) -> joining.beforeRead(membership) }
    }

    /** A replay is a read, including on an archived bond; erasure wins over its original content. */
    fun read(
        userId: UUID,
        entryId: EntryId,
    ): EntryView {
        val (entry, membership) = authorOf(userId, entryId, lock = false)
        joining.beforeRead(membership)
        val current = checkNotNull(entries.find(entryId))
        return EntryView(current.readBy(membership.asReader()), checkNotNull(days.find(entry.bondDayId)))
    }

    /**
     * The entry and the caller's membership of its bond, for the entry's
     * **author** — and [EntryNotFoundException] for everybody else, whatever
     * the reason: there is no such entry, the caller is not in its bond (the
     * guard's own "no such bond" is not passed on, since that would say the
     * entry exists), or the caller's partner wrote it. Every route by entry
     * id asks here, so the rule is written once.
     *
     * What it returns of the **entry** was read before any lock and grants
     * nothing but its bond, its day and its author, none of which change;
     * [change] reads the entry again under its lock before deciding anything.
     */
    private fun authorOf(
        userId: UUID,
        entryId: EntryId,
        lock: Boolean,
    ): Pair<Entry, BondMembership> = authorOrNull(userId, entryId, lock) ?: throw EntryNotFoundException()

    private fun authorOrNull(
        userId: UUID,
        entryId: EntryId,
        lock: Boolean,
    ): Pair<Entry, BondMembership>? {
        val entry = entries.find(entryId)
        val membership =
            try {
                entry?.let { if (lock) access.lockMembershipOf(userId, it.bondId) else access.membershipOf(userId, it.bondId) }
            } catch (_: NotFoundException) {
                null
            }
        return if (entry != null && membership != null && entry.authorMemberId == membership.memberId) entry to membership else null
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
