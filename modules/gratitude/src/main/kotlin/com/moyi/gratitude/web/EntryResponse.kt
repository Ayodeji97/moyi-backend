package com.moyi.gratitude.web

import com.moyi.gratitude.domain.EntryStatus
import com.moyi.gratitude.service.EntryView
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `201` from `POST /bonds/{bondId}/entries`: the entry exactly as its own
 * author is always allowed to see it (BR-1's first clause — `memberId ==
 * authorMemberId` needs no reveal to be true). Echoing [text] back is not a
 * leak of anything: the caller is the one who just wrote it.
 *
 * This is **not** the shape a partner's [com.moyi.gratitude.domain.Entry]
 * takes once BR-1 gates it — that is `GetToday`'s `LockedEntryResponse`
 * (Task 8), a distinct type on purpose, so a field added here can never
 * widen what a locked entry serialises to.
 *
 * [date] is the Bond-day's own date — the one [com.moyi.gratitude.domain.DayAssignment]
 * resolved, which may differ from a UTC reading of [createdAt] on either
 * side of midnight in the bond's zone (BR-3).
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
) {
    companion object {
        fun from(view: EntryView): EntryResponse =
            EntryResponse(
                id = view.entry.id.value,
                bondId = view.entry.bondId,
                date = view.day.date,
                authorMemberId = view.entry.authorMemberId,
                text = view.entry.text.value,
                status = view.entry.status,
                createdAt = view.entry.createdAt,
                intendedAt = view.entry.intendedAt,
            )
    }
}
