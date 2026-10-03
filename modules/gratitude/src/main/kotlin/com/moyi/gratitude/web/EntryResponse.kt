package com.moyi.gratitude.web

import com.moyi.gratitude.domain.EntryReading
import com.moyi.gratitude.domain.EntryStatus
import com.moyi.gratitude.domain.Readability
import com.moyi.gratitude.service.EntryView
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `201` from `POST /bonds/{bondId}/entries`, and — from `GET /bonds/{bondId}/today`
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
 * row still holds some. Nothing in this slice erases an entry, but a response
 * must already be able to say so: an `Idempotency-Key` replay of
 * `POST /entries` re-reads the entry as it is *now* (spec §5.4), and after an
 * erasure there are no words to return — never the ones the first response
 * carried.
 *
 * [date] is the Bond-day's own date — the one [com.moyi.gratitude.domain.DayAssignment]
 * resolved, which may differ from a UTC reading of [createdAt] on either
 * side of midnight in the bond's zone (BR-3).
 *
 * Implements [PartnerEntryResponse] so `TodayResponse.partnerEntry` can carry
 * either this or [LockedEntryResponse] behind one field — BR-1's own
 * decision is what picks which, in [PartnerEntryResponse.of].
 */
internal data class EntryResponse(
    val id: UUID,
    val bondId: UUID,
    val date: LocalDate,
    val authorMemberId: UUID,
    val text: String?,
    val status: EntryStatus,
    val createdAt: Instant,
    val intendedAt: Instant,
) : PartnerEntryResponse {
    companion object {
        /** `null` exactly when [of] is: BR-1 did not grant the caller this shape. */
        fun from(view: EntryView): EntryResponse? = of(view.entry, view.day.date)

        /**
         * The one mapping from an entry to this shape, and it takes an
         * [EntryReading] — an entry BR-1 has already been asked about
         * ([com.moyi.gratitude.domain.Entry.readBy]) — never an
         * [com.moyi.gratitude.domain.Entry]. There is no way to build this
         * response from an entry the gate has not seen.
         *
         * `FULL` carries the words; `TOMBSTONE` is this same shape with
         * `text = null` and `status = DELETED` ([EntryReading] answers both,
         * whatever the row still holds — one tombstone, not a second type).
         * `LOCKED` and `NOT_A_MEMBER` are **not this shape at all** and
         * answer `null`: a locked entry is [LockedEntryResponse]'s to render
         * ([PartnerEntryResponse.of]), and a non-member is shown nothing.
         */
        fun of(
            reading: EntryReading,
            date: LocalDate,
        ): EntryResponse? =
            when (reading.readability) {
                Readability.FULL, Readability.TOMBSTONE ->
                    EntryResponse(
                        id = reading.id.value,
                        bondId = reading.bondId,
                        date = date,
                        authorMemberId = reading.authorMemberId,
                        text = reading.text?.value,
                        status = reading.status,
                        createdAt = reading.createdAt,
                        intendedAt = reading.intendedAt,
                    )
                Readability.LOCKED, Readability.NOT_A_MEMBER -> null
            }
    }
}
