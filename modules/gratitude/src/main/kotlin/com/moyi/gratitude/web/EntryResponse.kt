package com.moyi.gratitude.web

import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryStatus
import com.moyi.gratitude.service.EntryView
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `201` from `POST /bonds/{bondId}/entries`, and — from `GET /bonds/{bondId}/today`
 * — any entry [com.moyi.gratitude.domain.Entry.canBeReadBy] grants the caller
 * in full: their own always (BR-1's first clause — `memberId ==
 * authorMemberId` needs no reveal to be true), and a partner's on whichever
 * later day BR-1's other two clauses grant it (`REVEALED`, or a closed
 * `SOLO`). Echoing [text] back to its own author is not a leak of anything —
 * they are the one who just wrote it; echoing it back as a partner's
 * revealed entry is BR-1 having already decided the caller may see it, not
 * this type deciding anything on its own.
 *
 * **This is not the shape a *locked* partner's [com.moyi.gratitude.domain.Entry]
 * takes.** That is [LockedEntryResponse], a distinct type on purpose, so a
 * field added here can never widen what a locked entry serialises to — see
 * its own KDoc for why that has to be a different type rather than this one
 * with fields nulled out.
 *
 * [date] is the Bond-day's own date — the one [com.moyi.gratitude.domain.DayAssignment]
 * resolved, which may differ from a UTC reading of [createdAt] on either
 * side of midnight in the bond's zone (BR-3).
 *
 * Implements [PartnerEntryResponse] so `TodayResponse.partnerEntry` can carry
 * either this or [LockedEntryResponse] behind one field — BR-1's own
 * decision is what picks which, in [TodayResponse.from].
 */
internal data class EntryResponse(
    val id: UUID,
    val bondId: UUID,
    val date: LocalDate,
    val authorMemberId: UUID,
    val text: String,
    val status: EntryStatus,
    val createdAt: Instant,
    val intendedAt: Instant,
) : PartnerEntryResponse {
    companion object {
        fun from(view: EntryView): EntryResponse = of(view.entry, view.day.date)

        /**
         * [from] with the Bond-day's date passed directly, for a caller that
         * already has one without an [EntryView] to unpack it from —
         * `GetToday`'s own use, for the caller's own entry in `TodayResponse`.
         * Both paths go through this single mapping so there is one place
         * that says what an author's own view of their entry contains.
         */
        fun of(
            entry: Entry,
            date: LocalDate,
        ): EntryResponse =
            EntryResponse(
                id = entry.id.value,
                bondId = entry.bondId,
                date = date,
                authorMemberId = entry.authorMemberId,
                text = entry.text.value,
                status = entry.status,
                createdAt = entry.createdAt,
                intendedAt = entry.intendedAt,
            )
    }
}
