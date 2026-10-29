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
import com.moyi.gratitude.infra.database.Favourites
import com.moyi.gratitude.infra.database.Reactions
import com.moyi.gratitude.infra.database.redacted
import com.moyi.media.api.AttachableKind
import com.moyi.media.api.MediaAttachments
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * What a `PATCH` asks of an entry's media (spec §4 D2, D3): a photo to attach,
 * if [image] is given, and a voice note, if [voice] is. Either absent leaves
 * the entry's as it is.
 */
internal data class MediaChange(
    val image: UUID?,
    val voice: UUID?,
) {
    companion object {
        val NONE = MediaChange(image = null, voice = null)
    }
}

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
    private val favourites: Favourites,
    private val reactions: Reactions,
    private val attachments: MediaAttachments,
    private val shown: EntryMedia,
) {
    fun change(
        userId: UUID,
        entryId: EntryId,
        replacement: EntryText?,
        media: MediaChange = MediaChange.NONE,
    ): EntryView {
        reconcileJoiningDay(userId, entryId)
        return changeUnderLocks(userId, entryId, replacement, media)
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
     * that takes no writes (`409 BOND_ARCHIVED`); and, under the entry's
     * lock, an entry that can no longer be edited (`409 ENTRY_IMMUTABLE`),
     * then a photo `media` will not attach to it (`422` on `imageMediaId`,
     * FR-044a), then a voice note (`422` on `voiceMediaId`). A delete by its
     * author meets none after the first.
     *
     * **Media are claimed under the entry's lock** (ADR-0048, ADR-0049), once
     * BR-7 has said the entry may still change, so nothing is ever attached to
     * an entry that was revealed meanwhile. A new photo or voice note in place
     * of another lets the old one go: it is its owner's alone again, as an
     * upload never attached is, which D5's reaper is to delete after 24 hours.
     * The same one again changes nothing. `PATCH` cannot remove either: an
     * absent `imageMediaId` or `voiceMediaId` leaves the entry's as it is.
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
        media: MediaChange = MediaChange.NONE,
    ): EntryView =
        try {
            checkNotNull(
                transactions.execute {
                    val (initial, membership) = authorOf(userId, entryId, lock = true)
                    if (replacement != null && (membership.hasLeft || !membership.isOpen)) throw BondArchivedException()
                    val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
                    val day = lockDays(membership, initial.bondDayId, now)
                    val (changed, nextDay) =
                        if (replacement == null) {
                            eraser.erase(entryId, day.id, now)
                        } else {
                            edit(userId, entryId, replacement, media, now) to day
                        }
                    // Not asked of the tables, because neither outcome can carry a mark or a reaction. An
                    // edit has just passed BR-7 under the entry's lock, so the entry is not revealed, and
                    // only a revealed entry can be marked or reacted to. A delete leaves a tombstone.
                    val reading = changed.readBy(membership.asReader())
                    EntryView(reading, nextDay, favourited = false, reaction = null, media = shown.of(membership.userId, reading))
                },
            )
        } catch (violation: DataIntegrityViolationException) {
            throw violation.redacted()
        }

    /**
     * Under the day's lock, which the caller took. The entry is read again under
     * its own before BR-7 is asked, and only then is a new photo or voice note
     * claimed, the photo first.
     */
    private fun edit(
        userId: UUID,
        entryId: EntryId,
        replacement: EntryText,
        media: MediaChange,
        now: Instant,
    ): Entry {
        val entry = entries.lockAndFind(entryId)
        if (!entry.isEditable) throw EntryImmutableException()
        var changed = entry.edit(replacement, now)
        val image = media.image
        if (image != null && image != entry.imageMediaId) {
            swap(userId, entryId, AttachableKind.IMAGE, claimed = image, replaced = entry.imageMediaId)
            changed = changed.withImage(image, now)
        }
        val voice = media.voice
        if (voice != null && voice != entry.voiceMediaId) {
            swap(userId, entryId, AttachableKind.VOICE, claimed = voice, replaced = entry.voiceMediaId)
            changed = changed.withVoice(voice, now)
        }
        if (changed != entry) entries.update(changed)
        return changed
    }

    /** `media`'s claim of [claimed] for the entry (FR-044a), then the release of what it replaces, if anything. */
    private fun swap(
        userId: UUID,
        entryId: EntryId,
        kind: AttachableKind,
        claimed: UUID,
        replaced: UUID?,
    ) {
        if (!attachments.claim(userId, claimed, kind, entryId.value)) throw MediaNotAttachableException(kind)
        replaced?.let { attachments.release(userId, it, entryId.value) }
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

    /**
     * A replay is a read, including on an archived bond; erasure wins over its original content.
     *
     * **Who has withdrawn is asked after the entry is read, not before**
     * ([readerNow], which has the reasoning). The membership [authorOf]
     * resolves decides who the author is and which joining day to reconcile.
     * It was taken before that reconcile, which can wait on the bond's lock
     * while an ending commits, so it does not decide what is shown: the
     * entry is loaded, and then the membership is resolved again for the
     * reader. This path holds the bond's lock only when the reconcile had a
     * day to resume, which is not a thing to rely on.
     *
     * A caller whose membership is gone by the second resolution is answered
     * as [authorOf] answers one who never had it: no such entry.
     */
    fun read(
        userId: UUID,
        entryId: EntryId,
    ): EntryView {
        val (entry, membership) = authorOf(userId, entryId, lock = false)
        joining.beforeRead(membership)
        val current = checkNotNull(entries.find(entryId))
        // After the entry, never before: the marker is read last.
        val reader =
            try {
                access.readerNow(membership)
            } catch (_: NotFoundException) {
                throw EntryNotFoundException()
            }
        val reading = current.readBy(reader)
        // As they are now: since the edit this replays, the entry may have been revealed, kept and reacted to.
        return EntryView(
            reading,
            checkNotNull(days.find(entry.bondDayId)),
            favourites.isMarkedBy(membership, reading),
            reactions.on(reading),
            shown.of(membership.userId, reading),
        )
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
