package com.moyi.gratitude.domain

import java.time.Instant
import java.util.UUID

/**
 * Doc 07 §2's `entries.status` `CHECK`. [SUBMITTED] is every entry this
 * slice ever produces — [REVEALED] arrives with the reveal slice's own
 * transition, [DELETED] with whichever slice adds `DELETE /entries/{id}`.
 * BR-2 already depends on [DELETED] existing before either of the others
 * lands: `entries_one_per_member_per_day` (V12) is a *partial* unique index
 * that excludes a deleted row, so an author who deletes before reveal may
 * write again that day — the status this enum carries is what a future
 * `delete()` would flip, not something this task adds.
 */
internal enum class EntryStatus { SUBMITTED, REVEALED, DELETED }

/**
 * One member's words for one [BondDay] (doc 04 §3, doc 07 §2) — a leaf, not
 * an aggregate of its own that holds a day: [BondDay] never carries a
 * `List<Entry>`, and this never carries a `BondDay`. `EntryStore` (Task 6)
 * finds one by [id] or by [bondDayId] alone; nothing here is walked from the
 * other (ADR-0026, V12's own comment on why `bond_day_id` is a plain column).
 *
 * **[text] is required, though `entries.text` allows `NULL`.** The column is
 * nullable for the media-only entry a later Phase 4 slice adds; this slice
 * refuses every media id at the edge (`422 MEDIA_NOT_YET_SUPPORTED`, Task 7),
 * so every [Entry] [submit] can produce already has words. Loosening this to
 * `EntryText?` is that later slice's change to make, not this one's to
 * pre-empt.
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
    val text: EntryText,
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
    /**
     * BR-1 — and in this slice only its first clause can ever be `true`: no
     * [BondDay] this module produces yet is [BondDayStatus.REVEALED], and
     * nothing produces [BondDayStatus.SOLO] either. Both arrive with later
     * slices; this method is written for the rule BR-1 states, not for the
     * subset of it C1 can reach, so it needs no change when they do.
     *
     * `day.status == SOLO && day.isClosed` restates BR-1's own conjunction
     * rather than collapsing it to `day.status == SOLO` — every
     * [BondDayStatus.SOLO] day is [BondDay.isClosed] by definition today, so
     * the second half is redundant as written. It stays spelled out on
     * purpose: BR-1 is stated as two conditions, and a status added to the
     * closed set later without BR-1's wording being revisited should fail
     * this comment's premise loudly rather than have this check silently
     * stop matching the rule it claims to implement.
     */
    fun canBeReadBy(
        memberId: UUID,
        day: BondDay,
    ): Boolean =
        memberId == authorMemberId ||
            day.status == BondDayStatus.REVEALED ||
            (day.status == BondDayStatus.SOLO && day.isClosed)

    companion object {
        /**
         * A member's write lands (`POST /bonds/{bondId}/entries`, Task 7).
         *
         * [intendedAt] is [DayAssignment]'s own input, already resolved by
         * the caller before this is reached — [submit] does not repeat
         * BR-3/BR-3a's clock-skew or offline-window checks, it only records
         * what the caller decided the day should be filed against.
         * [createdAt] and [updatedAt] start equal, as they do for every row
         * until its first edit; nothing this slice does ever produces a
         * second one.
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
