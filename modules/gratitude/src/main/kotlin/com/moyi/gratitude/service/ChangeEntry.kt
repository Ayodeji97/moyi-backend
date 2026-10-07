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
 * serializes editing against reveal, including the close job's
 * close sweep, which takes the same bond-then-day lock order.
 *
 * **An edit needs a bond that still takes writes; a delete does not.** Once
 * a bond has ended, or its member has left, or it is counting down to
 * deletion, the author's `PATCH` is `409 BOND_ARCHIVED` (ADR-0028: an ended
 * bond takes no new words) and the author's `DELETE` is still `204` (the
 * owner's ruling of 2026-10-06 on ADR-0032 question 1). Taking one's own
 * words back adds nothing to a record that is closed, and it is what a
 * withdrawal does to every entry at once: if a single delete were refused
 * there, a tombstone appearing after the end could only have come from a
 * withdrawal, and nothing is allowed to say that one happened (ADR-0028
 * decision 8). The erasure itself is [EraseEntry]'s, shared with the
 * withdrawal so the two cannot leave different rows.
 */
@Service
@Suppress("LongParameterList") // What changing an entry touches, each named.
internal class ChangeEntry(
    private val access: BondAccess,
    private val days: BondDayStore,
    private val entries: EntryStore,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val joining: ReconcileJoiningDay,
    private val eraser: EraseEntry,
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

    /**
     * [change] without its first step. For a caller that has already run
     * [reconcileJoiningDay] and has since opened a transaction of its own
     * ([PatchEntry], for the key): run again in there, the reconcile would
     * join that transaction — rolled back with a refusal after all — and
     * could lock the joining day before an older day this then locks.
     *
     * **The refusals, in the order a caller can meet them:** not the author
     * (`404`, the one answer everybody else gets); for an edit only, a bond
     * that takes no writes (`409 BOND_ARCHIVED`); media (`422`); and, under
     * the entry's lock, an entry that can no longer be edited (`409
     * ENTRY_IMMUTABLE`). A delete by its author meets none after the first.
     *
     * **Both paths lock through [lockDays] first**, which is what orders the
     * entry's day against the joining day. For a delete [EraseEntry] then
     * asks for the day's lock again: this transaction already holds it, so
     * the second request waits for nobody and changes no order. It costs
     * one statement, and it is the price of [EraseEntry] being safe for the
     * withdrawal, which has no joining day to order and calls it directly.
     */
    fun changeUnderLocks(
        userId: UUID,
        entryId: EntryId,
        replacement: EntryText?,
        unsupportedMedia: Boolean = false,
    ): EntryView =
        try {
            checkNotNull(
                transactions.execute {
                    val (initial, membership) = authorOf(userId, entryId, lock = true)
                    if (replacement != null && (membership.hasLeft || !membership.isOpen)) throw BondArchivedException()
                    if (unsupportedMedia) throw MediaNotYetSupportedException()
                    val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
                    val day = lockDays(membership, initial.bondDayId, now)
                    val (changed, nextDay) =
                        if (replacement == null) eraser.erase(entryId, day.id, now) else edit(entryId, replacement, now) to day
                    EntryView(changed.readBy(membership.asReader()), nextDay)
                },
            )
        } catch (violation: DataIntegrityViolationException) {
            throw violation.redacted()
        }

    /** Under the day's lock, which the caller took. The entry is read again under its own before BR-7 is asked. */
    private fun edit(
        entryId: EntryId,
        replacement: EntryText,
        now: Instant,
    ): Entry {
        val entry = entries.lockAndFind(entryId)
        if (!entry.isEditable) throw EntryImmutableException()
        val changed = entry.edit(replacement, now)
        if (changed != entry) entries.update(changed)
        return changed
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
