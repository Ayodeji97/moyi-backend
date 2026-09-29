package com.moyi.gratitude.service

import com.moyi.bond.api.BondMembership
import com.moyi.common.core.IdGenerator
import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayAssignment
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.infra.database.BondDayStore
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.gratitude.infra.database.GratitudeConstraints
import com.moyi.gratitude.infra.database.violates
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The wire request's contents, translated once at the web edge — the same
 * split `bond.web.CreateBondRequest.toDraft()` draws between a contract with
 * clients and a contract with this service.
 *
 * [text] is the raw, untrimmed string; [EntryText.of] does the one
 * normalisation and the three FR-041 limits, so this carries nothing that
 * could disagree with the domain about what "valid" means.
 *
 * [imageMediaId] and [voiceMediaId] exist so [SubmitEntry] can refuse them
 * (`422 MEDIA_NOT_YET_SUPPORTED`) rather than the web layer silently
 * dropping a field it does not recognise — spec §1 requires the refusal be
 * visible to the caller, not just to whoever reads this module's source.
 *
 * [intendedAt], BR-3a's offline-draft claim, is optional and rarely sent;
 * `null` means "filed against today, in the bond's own zone" — exactly
 * [DayAssignment.dateFor]'s own fallback.
 */
internal data class EntryDraft(
    val text: String,
    val imageMediaId: UUID?,
    val voiceMediaId: UUID?,
    val intendedAt: Instant?,
)

/** What [SubmitEntry.submit] hands back to the web layer: the row it wrote and the day it landed on. */
internal data class EntryView(
    val entry: Entry,
    val day: BondDay,
)

/**
 * `POST /bonds/{bondId}/entries` (spec §6.2): a member's words for the
 * Bond-day their own submission resolves to.
 *
 * **The check order is the contract**, and it continues the guard the
 * controller already ran: [membership] is already proof the caller belongs
 * to this bond (`BondAccess.membershipOf`, doc 06 §2/T-02), so nothing below
 * that could hand a non-member a different answer runs before it. In order:
 *
 * 1. `membership.hasLeft` — `409 BOND_ARCHIVED`. Checked explicitly, not
 *    inferred from [BondMembership.isOpen] — the second review of PR #41
 *    found `RequestDeletion.cancel` making exactly that assumption, and
 *    `BondMembership`'s own KDoc now requires both be checked.
 * 2. `!membership.isOpen` — `409 BOND_ARCHIVED` (BR-9).
 * 3. Either media id present — `422 MEDIA_NOT_YET_SUPPORTED` (spec §1):
 *    refused, never stored and quietly ignored.
 * 4. The day: [DayAssignment.dateFor] resolves which Bond-day this entry is
 *    for, then [BondDayStore.openOrGet] opens or finds it — `SUSPENDED`
 *    rather than `OPEN` while [BondMembership.awaitingSecondMember] (doc 04
 *    §8.3a, `02` J1: the creator may write before their partner joins).
 * 5. The insert. `entries_one_per_member_per_day` (V12/BR-2) is what
 *    refuses a second entry from the same member on the same day — this
 *    method attempts the write and catches the conflict, it does not read
 *    first and check: a read-then-insert here would race the very index it
 *    is trying to honour. See the boundary note below for why the catch has
 *    to sit where it does.
 *
 * **The day's row is locked before its `entryCount`/`status` are read for the
 * update** (fix round 1, I3): [BondDayStore.lockAndFind] takes the row lock
 * `bond`'s own writes already hold for a read-modify-write (ADR-0028,
 * `BondRepositories.lockRow`), then reads the day fresh under it.
 * [BondDay.withEntry] is computed from that fresh read, not from whatever
 * [BondDayStore.openOrGet] happened to return — two members submitting on
 * the same day concurrently is the ordinary case this product exists for,
 * not a race to leave to `@Version` alone: without the lock, both
 * transactions read the same `entryCount`, both compute `+1`, and the
 * second's flush trips the optimistic check as an uncaught
 * `ObjectOptimisticLockingFailureException` — a `500` on the normal path,
 * not the race this slice's own tests ever exercised until this fix.
 * `SubmitEntryConcurrencyTest` is the two-members-same-day proof.
 *
 * **One [TransactionTemplate] boundary, not `@Transactional`.** `RegisterUser.kt`
 * documents the trap this avoids: a `@Transactional` method that catches its
 * own [DataIntegrityViolationException] and then *returns normally* commits
 * against a transaction the flush has already marked rollback-only, which
 * fails with `UnexpectedRollbackException` instead of the response this
 * method means to give. That specific failure mode does not apply to a catch
 * that rethrows — Spring rolls back and propagates either way — but the
 * explicit boundary is kept regardless, for the same reason `RegisterUser`
 * keeps its own: the boundary is something a reader can see, rather than an
 * annotation whose extent has to be inferred from where the class starts.
 */
@Service
internal class SubmitEntry(
    private val days: BondDayStore,
    private val entries: EntryStore,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {
    /**
     * @throws BondArchivedException `membership.hasLeft`, or the bond has ended (BR-9)
     * @throws MediaNotYetSupportedException either media id is present (spec §1)
     * @throws DayClosedException the Bond-day this entry resolves to has already closed
     * @throws EntryAlreadyExistsException a second entry from this member on this day (BR-2)
     */
    @Suppress("ThrowsCount")
    fun submit(
        membership: BondMembership,
        draft: EntryDraft,
    ): EntryView {
        if (membership.hasLeft || !membership.isOpen) throw BondArchivedException()
        if (draft.imageMediaId != null || draft.voiceMediaId != null) throw MediaNotYetSupportedException()

        val text = EntryText.of(draft.text)
        val zone = ZoneId.of(membership.anchorTimezone)
        val now = clock.instant()
        val bondId = membership.bondId

        val view =
            try {
                transactions.execute {
                    val resolution =
                        DayAssignment.resolve(now, draft.intendedAt, zone) { candidate ->
                            days.statusOf(bondId, candidate)?.isClosed == true
                        }
                    val date = resolution.date
                    val openStatus = if (membership.awaitingSecondMember) BondDayStatus.SUSPENDED else BondDayStatus.OPEN
                    val opened = days.openOrGet(bondId, date, zone, now, openStatus)
                    // Locked, then re-read fresh under that lock — see the
                    // class KDoc's own note on I3. `opened`'s own entryCount/
                    // status is not used past this point; `day` is.
                    val day = days.lockAndFind(opened.id)
                    if (day.isClosed) throw DayClosedException()

                    val entry =
                        Entry.submit(
                            id = EntryId(ids.opaque()),
                            bondDayId = day.id,
                            bondId = bondId,
                            authorMemberId = membership.memberId,
                            text = text,
                            intendedAt = resolution.resolvedAt,
                            now = now,
                        )
                    // The flush BR-2's index lives on. See EntryStore.insert's
                    // own KDoc: the constraint is what refuses a second entry,
                    // raised here rather than deferred to a commit this catch
                    // could no longer be on the stack for.
                    entries.insert(entry)
                    val updated = day.withEntry()
                    days.update(updated)
                    EntryView(entry, updated)
                }
            } catch (violation: DataIntegrityViolationException) {
                if (!violation.violates(GratitudeConstraints.ENTRY_ONE_PER_MEMBER_PER_DAY)) throw violation
                throw EntryAlreadyExistsException()
            }

        return checkNotNull(view) { "submit's transaction produces an EntryView unless it threw" }
    }
}
