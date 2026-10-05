package com.moyi.gratitude.web

import com.moyi.gratitude.domain.EntryReading
import com.moyi.gratitude.domain.EntryStatus
import com.moyi.gratitude.service.EntryView
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `201` from `POST /bonds/{bondId}/entries`, `200` from
 * `PATCH /entries/{entryId}`, and — from `GET /bonds/{bondId}/today`
 * — any entry [com.moyi.gratitude.domain.Entry.canBeReadBy] grants the caller:
 * their own, and a partner's once it has been revealed (BR-1 keys on the
 * entry's own `revealedAt`, spec §4). Echoing [text] back to its own author
 * is not a leak of anything — they are the one who wrote it; echoing it back
 * as a partner's revealed entry is BR-1 having already decided the caller may
 * see it, not this type deciding anything on its own.
 *
 * **This is not the shape a *locked* partner's entry takes.** That is
 * [LockedEntryResponse], a distinct type on purpose, so a field added here
 * can never widen what a locked entry serialises to — see its own KDoc for
 * why that has to be a different type rather than this one with fields
 * nulled out.
 *
 * **[text] is `null` exactly on a tombstone**, and [status] is then
 * `DELETED`: the entry was deleted or withdrawn (BR-10/BR-10a) and the row
 * kept. BR-1 makes it a tombstone for everyone, its author included, and
 * [of] renders what the gate answered — so the text is absent even while the
 * row still holds some. **This wide tombstone is only for a reader who could
 * read the entry before it was erased** — its author, or a partner it had
 * been revealed to. A partner it was never revealed to gets
 * [ErasedEntryResponse], which has no id and no timestamps to give.
 * `DELETE /entries/{entryId}` is what erases an entry, and every response
 * that renders one afterwards says so: `GET /today`, and an
 * `Idempotency-Key` replay of `POST /entries` or `PATCH /entries/{entryId}`,
 * which re-reads the entry as it is *now* (spec §5.4) — after an erasure
 * there are no words to return, never the ones the first response carried.
 *
 * [date] is the Bond-day's own date — the one [com.moyi.gratitude.domain.DayAssignment]
 * resolved, which may differ from a UTC reading of [createdAt] on either
 * side of midnight in the bond's zone (BR-3).
 *
 * Implements [PartnerEntryResponse] so `TodayResponse.partnerEntry` can carry
 * this, [LockedEntryResponse] or [ErasedEntryResponse] behind one field —
 * BR-1's own decision is what picks which, in [PartnerEntryResponse.of].
 */
@ConsistentCopyVisibility
internal data class EntryResponse private constructor(
    val id: UUID,
    val bondId: UUID,
    val date: LocalDate,
    val authorMemberId: UUID,
    val text: String?,
    val status: EntryStatus,
    val createdAt: Instant,
    val intendedAt: Instant,
) : PartnerEntryResponse {
    /**
     * Never the words (doc 18 §5/§9): this is the one object in the module
     * that holds an entry's text as a plain `String` on its way *out*, and a
     * data class would print it. `TodayResponse` prints its entries through
     * this, so it is covered too. A tombstone prints `text=null` — there is
     * nothing to hide, and saying "redacted" would claim there was.
     */
    override fun toString(): String =
        "EntryResponse(id=$id, bondId=$bondId, date=$date, authorMemberId=$authorMemberId, " +
            "text=${if (text == null) "null" else "(redacted)"}, status=$status, createdAt=$createdAt, intendedAt=$intendedAt)"

    companion object {
        /** `null` exactly when [of] is: BR-1 did not grant the caller this shape. */
        fun from(view: EntryView): EntryResponse? = of(view.entry, view.day.date)

        /**
         * **The only way to build this response** — the constructor is
         * private, and this takes an [EntryReading]: an entry BR-1 has
         * already been asked about ([com.moyi.gratitude.domain.Entry.readBy]).
         * (`@ConsistentCopyVisibility` makes the data class's `copy` private
         * with it; otherwise `copy` is a second, public constructor.)
         *
         * It renders [EntryReading.disclosed] and nothing else, so it is
         * non-null exactly when the gate answered `FULL` (with the words) or
         * `TOMBSTONE` (`text = null`, `status = DELETED`). `LOCKED`,
         * `TOMBSTONE_UNSEEN` and `NOT_A_MEMBER` disclose nothing and are
         * **not this shape at all**: [PartnerEntryResponse.of] gives the
         * first two their own minimal types, and a non-member is shown
         * nothing.
         */
        fun of(
            reading: EntryReading,
            date: LocalDate,
        ): EntryResponse? =
            reading.disclosed?.let { entry ->
                EntryResponse(
                    id = entry.id.value,
                    bondId = entry.bondId,
                    date = date,
                    authorMemberId = entry.authorMemberId,
                    text = entry.text?.value,
                    status = entry.status,
                    createdAt = entry.createdAt,
                    intendedAt = entry.intendedAt,
                )
            }
    }
}
