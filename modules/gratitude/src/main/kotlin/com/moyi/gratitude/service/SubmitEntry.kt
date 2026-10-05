package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.common.core.IdGenerator
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.web.NotFoundException
import com.moyi.common.web.idempotency.IdempotentExecution
import com.moyi.common.web.idempotency.IdempotentRequest
import com.moyi.common.web.idempotency.IdempotentResult
import com.moyi.common.web.idempotency.ResultKind
import com.moyi.gratitude.domain.BondCalendar
import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayAssignment
import com.moyi.gratitude.domain.DayWindow
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryReading
import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.infra.database.BondDayStore
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.gratitude.infra.database.GratitudeConstraints
import com.moyi.gratitude.infra.database.redacted
import com.moyi.gratitude.infra.database.violates
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The wire request's contents, translated once at the web edge — the same
 * split `bond.web.CreateBondRequest.toDraft()` draws between a contract with
 * clients and a contract with this service.
 *
 * [text] is the raw string exactly as sent, and it is what is stored (ruling
 * P12); [EntryText.of] applies FR-041's limits to it, so this carries
 * nothing that could disagree with the domain about what "valid" means.
 *
 * [imageMediaId] and [voiceMediaId] exist so [SubmitEntry] can refuse them
 * (`422 MEDIA_NOT_YET_SUPPORTED`) rather than the web layer silently
 * dropping a field it does not recognise — spec §1 requires the refusal be
 * visible to the caller, not just to whoever reads this module's source.
 *
 * [intendedAt], BR-3a's offline-draft claim, is optional and rarely sent;
 * `null` means "filed against today, on the bond's own calendar" — exactly
 * [DayAssignment.resolve]'s own fallback.
 */
internal data class EntryDraft(
    val text: String,
    val imageMediaId: UUID?,
    val voiceMediaId: UUID?,
    val intendedAt: Instant?,
) {
    /** Never the words — `SubmitEntryRequest.toString`'s own reason: [text] is still a plain `String` here. */
    override fun toString(): String =
        "EntryDraft(text=(redacted), imageMediaId=$imageMediaId, voiceMediaId=$voiceMediaId, intendedAt=$intendedAt)"
}

/**
 * An entry **as BR-1 answered for the caller**, and the day it is filed on —
 * what the web layer renders. An [EntryReading], not an [Entry]: a fresh
 * write and a replay both leave this class through [Entry.readBy], so the
 * response is built from the gate's answer on either path.
 */
internal data class EntryView(
    val entry: EntryReading,
    val day: BondDay,
)

/**
 * What [SubmitEntry.submit] hands back to the web layer. [view] is the entry
 * **as it stands now** — on a replay, re-read, so possibly a tombstone.
 * [status] is the HTTP status the first attempt recorded under its
 * `Idempotency-Key`, and [replayed] is what `Idempotency-Replayed` reports.
 */
internal data class Submission(
    val view: EntryView,
    val status: Int,
    val replayed: Boolean,
)

/**
 * `POST /bonds/{bondId}/entries` (spec §6.2): a member's words for the
 * Bond-day their own submission resolves to.
 *
 * **One transaction holds the key and the write (spec §5.4).** [submit]
 * opens its transaction and runs everything inside [IdempotentExecution.once]:
 * the `Idempotency-Key`'s advisory lock, its reservation, the entry and the
 * key's completion commit together or not at all. A refusal below — any of
 * them — or a crash rolls the reservation back with the entry it never
 * produced, so the same key is free for a corrected retry at once rather
 * than stuck `409 IDEMPOTENCY_KEY_IN_FLIGHT` for 24 hours.
 *
 * **The full lock order is key, then bond, then bond-day, then entry.** The
 * key's lock is *tried*, never waited for ([IdempotentExecution]'s KDoc), so
 * it cannot close a wait cycle wherever it sits; it goes **first** so that a
 * second request under the same key is refused at once. The bond lock is
 * blocking: taken first, that second request would queue behind the first
 * one's bond lock before it could be told anything.
 *
 * **A replay re-reads; it never re-runs and never repeats a stored copy.**
 * When the key has already produced an entry, `once` returns its id and
 * [replay] reads it back. **A replay is authorised as a read of the result**,
 * by exactly the rule `GetToday` applies: [BondAccess.membershipOf] must
 * succeed (`404` for a caller who holds no membership), and neither
 * `hasLeft` nor `isOpen` refuses — `states.md` §9 keeps an ended bond a
 * readable archive for both former members, so a client whose `201` was
 * lost just before the bond ended still learns what its request produced.
 * Those two are *write* guards; the write already happened. Then the entry
 * *as it is now*: one erased since comes back as its tombstone, with no
 * text, because there is no second copy of the words to answer from (V11
 * stores identity only). **What is rendered is BR-1's answer, not this
 * class's**: the replay asks [Entry.readBy] exactly as `GetToday` does (spec
 * §4 names "replayed responses" as under the same gate), so a tombstone is a
 * tombstone here for the reason it is one there. The unlocked `membershipOf`, not
 * `lockMembershipOf`: a read never queues behind the bond lock.
 *
 * **Then bond, then bond-day, then entry, held to commit (spec §2.1).** The
 * first thing a fresh submission does inside the transaction — before
 * reading anything of the bond — is [BondAccess.lockMembershipOf],
 * which guards, takes the `bonds` row's `FOR UPDATE` and re-reads the
 * membership under it. A leave, block, deletion or zone confirmation (every
 * one of which takes that same lock first) therefore either commits before
 * this write reads the bond, or waits behind it until this write commits.
 * The old shape — the controller resolving membership outside any
 * transaction and this method checking `isOpen` on that stale copy — let an
 * entry commit after the bond had ended. Only then is the day's row locked
 * ([BondDayStore.lockAndFind]) and the entry inserted.
 *
 * **The check order is the contract**, and it starts with the guard:
 * [BondAccess.lockMembershipOf] answers a non-member with the same `404` as
 * a bond that does not exist (doc 06 §2, T-02), so nothing below that could
 * hand a non-member a different answer runs before it. In order:
 *
 * 1. `membership.hasLeft` — `409 BOND_ARCHIVED`. Checked explicitly, not
 *    inferred from [BondMembership.isOpen] — the second review of PR #41
 *    found `RequestDeletion.cancel` making exactly that assumption, and
 *    `BondMembership`'s own KDoc now requires both be checked.
 * 2. `!membership.isOpen` — `409 BOND_ARCHIVED` (BR-9).
 * 3. Either media id present — `422 MEDIA_NOT_YET_SUPPORTED` (spec §1):
 *    refused, never stored and quietly ignored.
 * 4. The day: [DayAssignment.resolve] resolves which Bond-day this entry is
 *    for against the bond's **effective-zone timeline**
 *    ([BondMembership.anchorTimeline], adapted by [asCalendar]) — not
 *    [BondMembership.anchorTimezone], which is the zone the bond *requests*
 *    and differs for up to a day after a change is confirmed (BR-6). Then
 *    [BondDayStore.openOrGet] opens or finds it with the span the timeline
 *    gave — `SUSPENDED` rather than `OPEN` while
 *    [BondMembership.awaitingSecondMember] (doc 04 §8.3a, `02` J1: the
 *    creator may write before their partner joins).
 *    **Then BR-3a is asked again, under the day's own lock** (spec §6.1.3).
 *    The settled-day check inside [DayAssignment.resolve] reads the day
 *    before any lock on it is held, and the close job takes no bond lock
 *    (plan R1), so a close can land between that read and
 *    [BondDayStore.lockAndFind]. If the day is settled once locked: an entry
 *    that was placed there by its `intendedAt` is **redirected once** to the
 *    day containing the submission instant — the words are kept, on a day
 *    that can still hold them — and anything else is `409 DAY_CLOSED`. The
 *    redirect does not recurse: a submission-time day that is itself settled
 *    is `409 DAY_CLOSED` too. It takes a second bond-day lock while holding
 *    the first; that is safe because every submitter of this bond is already
 *    serialised on the bond lock, and it obliges a lock-free writer (C3)
 *    never to hold two days of one bond at once.
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
 * [BondDayStore.openOrGet] happened to return. Two members of one bond now
 * also serialise on the bond lock before they get this far, so for the
 * submit path alone the day lock is belt to the bond lock's braces; it stays
 * because writers that take no bond lock (C3's close job, plan R1) contend
 * for the same row.
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
 * It is also what [BondAccess.lockMembershipOf]'s `MANDATORY` propagation
 * requires: the lock is only worth taking inside the transaction that does
 * the write.
 *
 * `LongParameterList` is suppressed on the constructor: seven collaborators
 * is what one transaction spanning the key, the bond, the day and the entry
 * takes, and bundling two of them to get under the threshold would hide
 * which of them this class actually uses.
 */
@Service
@Suppress("LongParameterList")
internal class SubmitEntry(
    private val access: BondAccess,
    private val days: BondDayStore,
    private val entries: EntryStore,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
    private val execution: IdempotentExecution,
    private val reveal: RevealDay,
    private val joining: ReconcileJoiningDay,
    private val events: EventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param idempotency the request's `Idempotency-Key`, as `IdempotencyInterceptor` prepared it
     * @throws com.moyi.common.web.NotFoundException the caller is not a member of [bondId], or there is no such bond
     * @throws BondArchivedException `membership.hasLeft`, or the bond has ended (BR-9)
     * @throws MediaNotYetSupportedException either media id is present (spec §1)
     * @throws DayClosedException the Bond-day this entry resolves to has already closed
     * @throws EntryAlreadyExistsException a second entry from this member on this day (BR-2)
     * @throws com.moyi.common.web.idempotency.IdempotencyKeyInFlightException the key's first request has not finished
     * @throws com.moyi.common.web.idempotency.IdempotencyKeyReusedException the key was first used for a different request
     */
    @Suppress("ThrowsCount")
    fun submit(
        userId: UUID,
        bondId: UUID,
        draft: EntryDraft,
        idempotency: IdempotentRequest,
    ): Submission {
        require(idempotency.userId == userId) { "the Idempotency-Key was prepared for a different caller than the one submitting" }
        // Pure validation of the caller's own request, no read: `@ValidEntryText`
        // has already run this same factory at the web edge, so it cannot fail
        // here for a request that reached this method.
        val text = EntryText.of(draft.text)
        // Truncated once, here, to what `timestamptz` keeps: a fresh `201`
        // renders these instants from memory and a replay from the row, and
        // the two must not differ below the microsecond.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        // The client's claim likewise, and before anything is decided from it.
        // Truncated, not left to the driver: pgjdbc rounds, and the last
        // half-microsecond of a day would round up into the next one —
        // stored outside the span of the day it was filed on.
        val intendedAt = draft.intendedAt?.truncatedTo(ChronoUnit.MICROS)

        val submission =
            try {
                transactions.execute {
                    // Key first (tried, never waited for), then bond, then
                    // bond-day, then entry — see the class KDoc.
                    val outcome =
                        execution.once(idempotency) {
                            // Spec §2.1. The lock is taken INSIDE the transaction
                            // and before anything of the bond is read, so a leave,
                            // block, deletion or zone confirmation either completes
                            // before this write begins or waits behind it. Reading
                            // membership outside the transaction and checking
                            // `isOpen` would let this commit after the bond ended.
                            val membership = access.lockMembershipOf(userId, bondId)
                            if (membership.hasLeft || !membership.isOpen) throw BondArchivedException()
                            if (draft.imageMediaId != null || draft.voiceMediaId != null) throw MediaNotYetSupportedException()
                            val (view, entryId) = write(membership, text, intendedAt, now)
                            // The key records what this produced by identity, never the words.
                            IdempotentResult(view, entryId.value, ResultKind.ENTRY, CREATED)
                        }
                    val view = outcome.value ?: replay(userId, bondId, EntryId(outcome.resultId))
                    Submission(view, outcome.status, outcome.wasReplayed)
                }
            } catch (violation: DataIntegrityViolationException) {
                if (!violation.violates(GratitudeConstraints.ENTRY_ONE_PER_MEMBER_PER_DAY)) throw violation.redacted()
                throw EntryAlreadyExistsException()
            }

        return checkNotNull(submission) { "submit's transaction produces a Submission unless it threw" }
    }

    /**
     * The entry a key already produced, **read as it stands now** and
     * authorised as a read — see the class KDoc. No `hasLeft`/`isOpen` check:
     * those refuse writes, and this writes nothing. The mismatches below
     * cannot happen for a key this class wrote (the path it was stored under
     * names [bondId], and its caller wrote the entry), so each answers as "no
     * such thing" rather than trusting a row that does not fit. The entry is
     * handed on as BR-1 answers for this caller ([Entry.readBy]), never raw.
     */
    private fun replay(
        userId: UUID,
        bondId: UUID,
        entryId: EntryId,
    ): EntryView {
        val membership = access.membershipOf(userId, bondId)
        joining.beforeRead(membership)
        // The author guard is the replay's own: a key answers only the member
        // whose request it recorded. What that member may then SEE is BR-1's
        // question, asked of the same gate `GetToday` asks.
        val reading =
            entries
                .find(entryId)
                ?.takeIf { it.authorMemberId == membership.memberId }
                ?.readBy(membership.asReader())
        // The day is found only for a reading that discloses which day it is
        // on: the author's own entry, in full or as its tombstone. Anything
        // else — an entry of another bond, above all — has none to give.
        val day = reading?.disclosed?.let { days.find(it.bondDayId) }
        if (reading == null || day == null) throw NotFoundException("That entry was not found.")
        return EntryView(reading, day)
    }

    /**
     * Steps 4 and 5 of the class KDoc, under the bond lock [submit] already
     * holds. Returns the entry's id beside its view: the key records the id,
     * and a reading only discloses one to a reader BR-1 grants it to.
     */
    private fun write(
        membership: BondMembership,
        text: EntryText,
        intendedAt: Instant?,
        now: Instant,
    ): Pair<EntryView, EntryId> {
        val bondId = membership.bondId
        val timeline = membership.anchorTimeline
        val calendar = timeline.asCalendar()
        val claimed =
            DayAssignment.resolve(now, intendedAt, calendar) { candidate ->
                days.findByBondAndDate(bondId, candidate)?.isSettled == true
            }
        // BR-3a decided against the client's claim. Why is `resolve`'s to say
        // (`claimed.claim`), never worked out again here. The bond, the reason
        // and the date it was filed on — never the words, the claimed instant
        // or the member.
        when (claimed.claim) {
            // On a day the member did not name, so it is on the record at
            // INFO: this line is what answers "why is my entry on that day".
            DayAssignment.Claim.TOO_OLD, DayAssignment.Claim.BEFORE_THE_BOND, DayAssignment.Claim.DAY_SETTLED -> {
                log.info(FALLBACK_LOG, bondId, claimed.claim, claimed.date)
            }

            // On today, which is where the member expects it — and a client
            // whose clock runs a little fast would write this on every
            // submission it ever makes (ruling P11). DEBUG.
            DayAssignment.Claim.AHEAD_OF_CLOCK -> {
                log.debug(FALLBACK_LOG, bondId, claimed.claim, claimed.date)
            }

            DayAssignment.Claim.USED, DayAssignment.Claim.ABSENT -> {
                // Nothing was refused, so there is nothing to explain.
            }
        }
        val joiningDate = joining.window(membership)?.date
        if (joiningDate != null && !joiningDate.isAfter(claimed.date)) joining.underBondLock(membership, now)
        val claimedDay = openAndLock(membership, claimed.bounds, now)
        // BR-3a, rechecked under the day's own lock (spec §6.1.3). The check
        // inside `resolve` read the day before this lock was held, and the
        // close job takes no bond lock (plan R1), so a close can land in
        // between. See step 4 of the class KDoc.
        val (resolution, day) =
            if (!claimedDay.isSettled) {
                claimed to claimedDay
            } else {
                redirectOnce(membership, claimed, calendar, now) ?: throw DayClosedException()
            }

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
        // The flush BR-2's index lives on. See EntryStore.insert's own KDoc:
        // the constraint is what refuses a second entry, raised here rather
        // than deferred to a commit this catch could no longer be on the
        // stack for.
        entries.insert(entry)
        events.publish(OutboxEvent("Entry", entry.id.value, "EntrySubmitted", mapOf("bondId" to bondId, "bondDayId" to day.id.value), now))
        val updated = reveal.apply(day.withEntry(), membership.revealTimeLocal, now)
        if (joiningDate != null && joiningDate.isAfter(claimed.date)) joining.underBondLock(membership, now)
        val persisted = checkNotNull(entries.find(entry.id))
        return EntryView(persisted.readBy(membership.asReader()), updated) to entry.id
    }

    /**
     * BR-3a's one redirect (spec §6.1.3): [claimed]'s day turned out settled
     * once locked, so the entry goes to the day containing the submission
     * instant instead — resolved through the same [calendar], never a computed
     * midnight. `null` when there is nowhere to redirect to: the entry was
     * never placed by its `intendedAt` (it is *on* the submission-time day
     * already), or that day is settled too — which includes a claim that
     * named the submission-time day itself: locking it again is harmless, and
     * it is as settled the second time.
     * Not recursive, by construction: it resolves with no claim, and asks once.
     */
    private fun redirectOnce(
        membership: BondMembership,
        claimed: DayAssignment.Resolution,
        calendar: BondCalendar,
        now: Instant,
    ): Pair<DayAssignment.Resolution, BondDay>? {
        if (!claimed.usedIntendedAt) return null
        val fallback = DayAssignment.resolve(now, null, calendar) { false }
        // The claimed day is already locked. Reconcile the joining day before
        // today's row so a redirected offline write keeps chronological order.
        val joiningDate = joining.window(membership)?.date
        if (joiningDate != null && !joiningDate.isBefore(claimed.date)) joining.underBondLock(membership, now)
        val fallbackDay = openAndLock(membership, fallback.bounds, now)
        // The rare one: a close landed between the unlocked check and the
        // lock. Logged only once the fallback day's own settled check has
        // answered — said before it, this line claimed a redirect for a
        // request that was then a 409. Ids and dates, never the words.
        val outcome =
            if (fallbackDay.isSettled) "and {} is settled too; the entry is refused" else "an offline entry is redirected to {}"
        log.info("BR-3a: bond {} day {} was settled once locked; $outcome", membership.bondId, claimed.date, fallback.date)
        return (fallback to fallbackDay).takeUnless { fallbackDay.isSettled }
    }

    /**
     * Opens (or finds) the day for [window] and returns it **locked and
     * re-read under that lock** — the class KDoc's note on I3. What
     * [BondDayStore.openOrGet] itself returned is not used: its
     * `entryCount`/`status`/`closedAt` predate the lock.
     *
     * **Then the row's span is brought up to [window]** (ruling P10): a row
     * opened before a westward anchor change still ends where the calendar
     * said then, and the calendar now runs the day on to its successor's
     * start (plan R3). [BondDay.extendedTo] moves `ends_at` later — only
     * later, and only on an unsettled day — here, under the day's lock and
     * before any entry is inserted, so no entry is ever filed on a row whose
     * span does not contain it. Both callers come through here, the BR-3a
     * redirect included.
     */
    private fun openAndLock(
        membership: BondMembership,
        window: DayWindow,
        now: Instant,
    ): BondDay {
        // The snapshot `anchor_timezone` keeps: the zone in force when this
        // day began. Taken at `startsAt`, not at `now` or `resolvedAt`, so
        // whichever writer opens the row stamps the same zone. Relies on
        // `dayAt` never returning an empty window: an instant always lies in one.
        val zone = ZoneId.of(membership.anchorTimeline.zoneIdAt(window.startsAt))
        val openStatus = if (membership.awaitingSecondMember) BondDayStatus.SUSPENDED else BondDayStatus.OPEN
        val opened = days.openOrGet(membership.bondId, window, zone, now, openStatus)
        val locked = days.lockAndFind(opened.id)
        val extended = locked.extendedTo(window).resumeJoiningDay(membership.activeSince)
        if (extended != locked) days.update(extended)
        return extended
    }

    private companion object {
        /** `201` — what a fresh submission answers, and so what its key replays. */
        const val CREATED = 201

        /** A BR-3a fallback: the bond, why the claim was not used (a [DayAssignment.Claim]), and the date filed on. */
        const val FALLBACK_LOG = "BR-3a: an intendedAt was not used for bond {} ({}); the entry is filed by submission time, on {}"
    }
}
