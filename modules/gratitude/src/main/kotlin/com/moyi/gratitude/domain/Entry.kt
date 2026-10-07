package com.moyi.gratitude.domain

import java.time.Instant
import java.util.UUID

/**
 * Doc 07 §2's `entries.status` `CHECK`. [SUBMITTED] is what [Entry.submit]
 * produces, [REVEALED] what [Entry.reveal] makes of it, and [DELETED] what
 * [Entry.erase] leaves.
 *
 * **BR-2's slot is freed by `deleted_at`, not by [status] alone** (whole-
 * branch review, F5 — an earlier version of this KDoc said the opposite).
 * `entries_one_per_member_per_day` (V12) is `WHERE deleted_at IS NULL`, a
 * *partial* unique index keyed on that column, not on `status`. An erasure
 * written from the wrong premise — setting `status = DELETED` and leaving
 * `deleted_at` null — would leave the slot held: the index would still see
 * a live row, and the author who "deleted" it could never write that day
 * again. [Entry.erase] sets both. On the read side, `EntryStore.findForDay`
 * returns a [DELETED] row along with the live ones, so one author can have
 * more than one row on a day; its KDoc says how `GetToday` chooses.
 */
internal enum class EntryStatus { SUBMITTED, REVEALED, DELETED }

/**
 * One member's words for one [BondDay] (doc 04 §3, doc 07 §2) — a leaf, not
 * an aggregate of its own that holds a day: [BondDay] never carries a
 * `List<Entry>`, and this never carries a `BondDay`. `EntryStore` (Task 6)
 * finds every entry for a [bondDayId], never a [BondDay] for an entry's id —
 * nothing here is walked from the other (ADR-0026, V12's own comment on why
 * `bond_day_id` is a plain column).
 *
 * **[text] is `null` exactly on a tombstone** — an entry content-erased by
 * BR-10/BR-10a (text and media references nulled, `status = DELETED`, the
 * row kept). [erase] is what makes one, and every *read* has to be able to
 * render it: `GET /today` shows it, and an `Idempotency-Key` replay
 * re-reads the entry from its current state (spec §5.4), where a row erased
 * since has no words to hand back, by design. Every entry [submit] produces
 * has words — `entries.text` is also nullable for the media-only entry a
 * later Phase 4 slice adds, which is refused for now
 * (`422 MEDIA_NOT_YET_SUPPORTED`) — so the `init` below admits a missing
 * text only where the status or `deletedAt` says why. Loosening it for
 * media-only entries is that later slice's change to make.
 *
 * [imageMediaId], [voiceMediaId], [voiceDurationMs] and [promptId] are
 * carried anyway, always `null` for now, so a row [submit] builds already
 * has a value for every column V12 declared — the same reason
 * [com.moyi.bond.domain.Bond] carries `timezoneChangedAt` and
 * `deletionRequestedAt` long before the slices that ever set them exist.
 * `entries.text_search` is deliberately **not** one of them: it is
 * Postgres's own derived search artifact (C6), never read or written by
 * anything this aggregate does, so it has no seat on the domain object at
 * all — it belongs on the persistence entity alone, once C6 populates it.
 *
 * No `@Suppress("LongParameterList")` needed for the sixteen properties
 * below: detekt's `LongParameterList` ships with `ignoreDataClasses: true`
 * by default, this project's `config/detekt/detekt.yml` never overrides it,
 * and `Entry` is a `data class` — the same reason
 * [com.moyi.bond.domain.Bond] (fifteen properties) carries no suppression
 * either. `BondMembership` in `bond.api` needs one because it is a plain
 * `class`, not a `data class`; that is a different rule shape, not a
 * threshold this one sits under and `Entry` sits over.
 */
internal data class Entry(
    val id: EntryId,
    val bondDayId: BondDayId,
    val bondId: UUID,
    val authorMemberId: UUID,
    val text: EntryText?,
    val imageMediaId: UUID?,
    val voiceMediaId: UUID?,
    val voiceDurationMs: Int?,
    val promptId: UUID?,
    val status: EntryStatus,
    val authorDeletedAccount: Boolean,
    val createdAt: Instant,
    val intendedAt: Instant,
    val updatedAt: Instant,
    val revealedAt: Instant?,
    val deletedAt: Instant?,
) {
    init {
        // Either mark of an erasure admits a missing text — the same two
        // `canBeReadBy` counts as one. A row whose erasure nulled the text
        // and stamped `deleted_at` before it flipped the status must load,
        // and render as its tombstone, not fail in the mapper.
        require(text != null || status == EntryStatus.DELETED || deletedAt != null) {
            "only an erased entry has no text"
        }
    }

    /**
     * BR-1, in the order the spec states it (§4, as revised):
     *
     * 1. **Membership first.** A revealed entry is not public: a [reader]
     *    whose membership is of another bond gets [Readability.NOT_A_MEMBER]
     *    before any question about content is asked.
     * 2. **Erasure beats everything.** A deleted or withdrawn entry is a
     *    tombstone for both people, its author included — there is one copy
     *    of the text and BR-10a makes the erasure total. Either mark of an
     *    erasure counts, [deletedAt] or [EntryStatus.DELETED]: an erasure
     *    that has set one and not yet the other is already an erasure.
     *    **And so does a withdrawal that has erased nothing yet**: an entry
     *    whose author is among [Reader.withdrawnAuthors] is answered exactly
     *    as an erased one. `bond` records a withdrawal in the transaction
     *    that ends the bond, and the outbox's consumer erases the rows some
     *    seconds later, or much later if the poller is stopped. Spec §6.7
     *    does not allow the words to be read in that interval, so the gate
     *    does not wait for the rows. It is asked **here and nowhere else**
     *    because every response that carries an entry is built from this
     *    function's answer (`GET /today`, a fresh write, a replay): a second
     *    place that asked would be a second copy of the rule, and a response
     *    added later that forgot to ask it would show the words, where one
     *    that goes through this gate cannot. **What it is told is only as
     *    current as the [Reader]**: this function cannot know when
     *    [Reader.withdrawnAuthors] was read. The rule that makes its answer
     *    safe is the caller's, "the marker is read last": the reader is built
     *    after this entry was loaded (or under the bond's lock), so a set
     *    that names nobody means the ending had not committed when the entry
     *    was read. [isErased] is deliberately not
     *    where it lives: that is the row's own state, which the edit rule and
     *    the reveal also read, and neither has a reader to ask about.
     *    **Which tombstone depends on what the reader could see before**:
     *    [Readability.TOMBSTONE] for a reader the entry was ever readable to
     *    (clause 3), [Readability.TOMBSTONE_UNSEEN] for a partner it was
     *    never revealed to — an erasure must not hand that reader the id and
     *    timestamps BR-8 withheld while the entry was live.
     * 3. **Otherwise: your own words, or a revealed entry.** Keyed on
     *    [revealedAt], **not** on the day's status — which is why this takes
     *    no `BondDay` at all. A solo day that reveals and is then frozen still
     *    has `revealedAt` set, and words the partner has already read must not
     *    become unreadable because a later transition rewrote the day; nor
     *    does a `FROZEN`, `REVEALED` or `SOLO` status reveal an entry whose
     *    own timestamp was never set. The timestamp is monotonic — nothing in
     *    this phase clears it.
     *
     * [reveal] stamps [revealedAt], in the transaction that reveals the day; later status changes never clear it.
     *
     * Callers that render want [readBy], which returns the answer bound to
     * the entry it was given for.
     */
    fun canBeReadBy(reader: Reader): Readability {
        val erased = isErased || authorMemberId in reader.withdrawnAuthors
        val everReadable = authorMemberId == reader.memberId || revealedAt != null
        return when {
            reader.bondId != bondId -> Readability.NOT_A_MEMBER
            erased && everReadable -> Readability.TOMBSTONE
            erased -> Readability.TOMBSTONE_UNSEEN
            everReadable -> Readability.FULL
            else -> Readability.LOCKED
        }
    }

    /** This entry as [reader] may see it — the only form an entry is rendered from ([EntryReading]). */
    fun readBy(reader: Reader): EntryReading = EntryReading.of(this, reader)

    /**
     * Either mark of an erasure counts ([canBeReadBy], clause 2). Asked here
     * by everything that needs to know — the read gate, the edit rule, the
     * reveal — so the three cannot come to disagree about a half-erased row.
     *
     * **The row's own state, and only that.** A withdrawal that has not yet
     * reached this row does not make it erased; the read gate adds that
     * itself, from its reader ([canBeReadBy]).
     */
    val isErased: Boolean get() = deletedAt != null || status == EntryStatus.DELETED

    /** BR-7 is a domain rule; the service only translates its refusal to HTTP. */

    val isEditable: Boolean get() = !isErased && revealedAt == null && status != EntryStatus.REVEALED

    /** Replaces the author's words before anyone else has been entitled to read them. */
    fun edit(
        replacement: EntryText,
        now: Instant,
    ): Entry {
        check(isEditable) { "a revealed or erased entry is immutable" }
        return if (text == replacement) this else copy(text = replacement, updatedAt = now)
    }

    /** Erasure is idempotent, frees BR-2's slot, and never clears the reveal stamp. */
    fun erase(now: Instant): Entry =
        if (isErased) {
            this
        } else {
            copy(
                text = null,
                imageMediaId = null,
                voiceMediaId = null,
                voiceDurationMs = null,
                status = EntryStatus.DELETED,
                deletedAt = now,
                updatedAt = now,
            )
        }

    /** Reveal is monotonic, including across erasure and later lifecycle transitions. */
    fun reveal(now: Instant): Entry =
        if (revealedAt != null || isErased) {
            this
        } else {
            copy(status = EntryStatus.REVEALED, revealedAt = now, updatedAt = now)
        }

    companion object {
        /**
         * A member's write lands (`POST /bonds/{bondId}/entries`, Task 7).
         *
         * [intendedAt] is [DayAssignment.Resolution.resolvedAt], already
         * resolved by the caller before this is reached — [submit] does not
         * repeat BR-3/BR-3a's ahead-of-now or offline-window checks, it only
         * records the instant [DayAssignment.resolve] decided the day should
         * be filed against. **This is not the same value as the client's raw
         * `intendedAt` claim** (whole-branch review, F1): `SubmitEntry` must
         * pass [DayAssignment.resolve]'s `resolvedAt`, never
         * `draft.intendedAt` directly — a claim [DayAssignment.resolve]
         * rejected (too far ahead, too stale, or landing on a closed day)
         * resolves to the submission instant instead, so what lands here is
         * always the day this row is actually filed against, never an
         * unvalidated value a caller typed.
         * [createdAt] and [updatedAt] start equal, as they do for every row
         * until it first changes: [edit], [erase] and [reveal] each move
         * [updatedAt] when they change the entry, and nothing moves
         * [createdAt].
         *
         * `@Suppress("LongParameterList")` here, on the function rather than
         * the class (`Entry` itself needs none — detekt's `LongParameterList`
         * ships `ignoreDataClasses: true`, and this project never overrides
         * it): seven parameters is the row's own arity, each one a distinct
         * fact the caller already has in hand, and there is no `BondDraft`-
         * shaped request object to bundle them into here — that pattern
         * belongs to a web-layer input doc 06 §3.3 defines, and this is pure
         * domain with nothing upstream of it in this module.
         */
        @Suppress("LongParameterList")
        fun submit(
            id: EntryId,
            bondDayId: BondDayId,
            bondId: UUID,
            authorMemberId: UUID,
            text: EntryText,
            intendedAt: Instant,
            now: Instant,
        ): Entry =
            Entry(
                id = id,
                bondDayId = bondDayId,
                bondId = bondId,
                authorMemberId = authorMemberId,
                text = text,
                imageMediaId = null,
                voiceMediaId = null,
                voiceDurationMs = null,
                promptId = null,
                status = EntryStatus.SUBMITTED,
                authorDeletedAccount = false,
                createdAt = now,
                intendedAt = intendedAt,
                updatedAt = now,
                revealedAt = null,
                deletedAt = null,
            )
    }
}
