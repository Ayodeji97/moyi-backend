package com.moyi.gratitude.web

import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.service.TodayView
import java.time.LocalDate

/**
 * `200` from `GET /bonds/{bondId}/today` (spec §6.1).
 *
 * **[bondDay]'s [BondDayResponse.status] is returned deliberately, not
 * withheld out of caution.** Doc 04 §6.1: a member who has not written yet
 * can infer from [BondDayStatus.PARTIAL] that their partner has, and the
 * "Waiting for…" state the product shows depends on that inference being
 * available. What stays forbidden is anything about *reading, presence or
 * activity* beyond the status itself — [myEntry] and [partnerEntry] are the
 * only two places any content can appear, and BR-1 gates both.
 *
 * [myEntry] is the caller's own entry, in the same shape [EntryResponse]
 * already gives a fresh submission — `null` until they have written today.
 *
 * [partnerEntry] is [PartnerEntryResponse]`?` — [EntryResponse] (in full,
 * when [com.moyi.gratitude.domain.Entry.canBeReadBy] grants it) or
 * [LockedEntryResponse] (when it does not), or `null` when there is no
 * partner entry to report at all. **C1 itself only ever produces the last
 * two** — no status C1 assigns (`OPEN`, `PARTIAL`, `SUSPENDED`) ever satisfies
 * BR-1's reveal clauses, so a partner's entry is always locked here, never
 * revealed — but [TodayResponse.from] asks [com.moyi.gratitude.domain.Entry.canBeReadBy]
 * the same question C2's `REVEALED` and C3's `SOLO` will answer differently,
 * rather than hard-coding "always locked" and leaving a later slice to
 * discover that this response type never actually reveals anything. Doc 12's
 * reveal-gate test proves exactly this: flip [com.moyi.gratitude.domain.Entry.canBeReadBy]
 * to always `true` and a partner's entry stops being a [LockedEntryResponse]
 * and becomes a full [EntryResponse] instead — the failure the gate's own
 * test is written to catch.
 *
 * **`streak` and `prompt` are absent, not `null` placeholders.** They arrive
 * in C4 and C6 respectively (doc 06 §3.3's own promise for `BondResponse`
 * makes the same call). A field that does not exist yet is not part of this
 * contract; a client reading this response today learns nothing about
 * either by their absence, and a later slice adding them is additive, not a
 * breaking change.
 *
 * **No `partner` field.** The plan's own shape for this response names one
 * alongside `bondDay`, `myEntry` and `partnerEntry`, and it is left out of
 * `GetToday`'s implementation on purpose, not by oversight: nothing this
 * task has access to can answer it correctly. [com.moyi.bond.api.BondMembership]
 * carries only the caller's own facts (their own member id, their own
 * user id) — it names no fact about who the other member of the bond even
 * is, so there is no id here to resolve a display name for through
 * `identity.api.UserDirectory` even once one has written (and, unwritten,
 * there is no entry to infer one from either). Inventing a shape for a field
 * this module cannot yet populate correctly risks exactly the failure mode
 * BR-8 exists to prevent elsewhere in this same response — a field that
 * looks informative and is actually wrong, absent, or a placeholder nobody
 * reads carefully enough to notice is empty. Surfacing a partner's identity
 * is `bond.api.BondAccess`'s own gap to close, not `gratitude`'s to work
 * around; flagged here for the controller rather than guessed at.
 */
internal data class TodayResponse(
    val bondDay: BondDayResponse,
    val myEntry: EntryResponse?,
    val partnerEntry: PartnerEntryResponse?,
) {
    companion object {
        fun from(view: TodayView): TodayResponse =
            TodayResponse(
                bondDay = BondDayResponse(date = view.date, status = view.status),
                myEntry = view.myEntry?.let { EntryResponse.of(it, view.date) },
                partnerEntry =
                    view.partnerEntry?.let { entry ->
                        if (view.partnerEntryVisible) EntryResponse.of(entry, view.date) else LockedEntryResponse(entry.authorMemberId)
                    },
            )
    }
}

/**
 * `myEntry`/`partnerEntry`'s shared type: either [EntryResponse] (BR-1
 * granted the read) or [LockedEntryResponse] (it did not). A marker
 * interface rather than a sealed class with its own fields — nothing about
 * "an entry response, revealed or locked" needs stating twice, and Jackson
 * serialises each implementation by its own runtime shape without needing
 * `@JsonTypeInfo` here: this type is only ever written to the wire, never
 * read back off it.
 */
internal sealed interface PartnerEntryResponse

/**
 * The Bond-day itself, as much of it as any caller in this bond may see:
 * which date it is and where it stands. Not [com.moyi.gratitude.domain.BondDay]
 * serialised wholesale — no `entryCount` (redundant with [status] at the
 * granularity this API exposes: at most two entries, and [status] already
 * says whether one or both have arrived), no `version`, no `anchorTimezone`
 * repeated back (the bond's own response already carries it).
 */
internal data class BondDayResponse(
    val date: LocalDate,
    val status: BondDayStatus,
)
