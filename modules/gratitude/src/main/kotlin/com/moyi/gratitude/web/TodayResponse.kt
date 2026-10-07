package com.moyi.gratitude.web

import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.EntryReading
import com.moyi.gratitude.domain.Readability
import com.moyi.gratitude.service.TodayView
import java.time.LocalDate

/**
 * `200` from `GET /bonds/{bondId}/today` (spec §5.1).
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
 * It is rendered from BR-1's answer like any other entry (`GetToday`'s own
 * KDoc), so an entry the caller withdrew is its tombstone here too.
 *
 * [partnerEntry] is [PartnerEntryResponse]`?` — [EntryResponse] (in full once
 * the entry has been revealed, or its tombstone if withdrawn after that),
 * [LockedEntryResponse] (not yet revealed), [ErasedEntryResponse] (withdrawn
 * without ever having been revealed), or `null` when there is no partner
 * entry to report at all. **All four are reachable through the API**: a
 * partner's entry is locked until it is revealed, in full after that, and
 * one tombstone or the other once its author deletes it, depending on which
 * side of the reveal the delete fell. None of that is decided here —
 * [PartnerEntryResponse.of] renders whatever
 * [com.moyi.gratitude.domain.Entry.canBeReadBy] answered rather than
 * choosing a shape from the day or the entry itself. Doc 12's
 * reveal-gate test proves exactly this: make the gate answer `FULL` always
 * and a partner's entry stops being a [LockedEntryResponse] and becomes a
 * full [EntryResponse] instead — the failure the gate's own test is written
 * to catch.
 *
 * **`prompt` is absent, not a `null` placeholder.** It arrives in C6 (doc 06
 * §3.3's own promise for `BondResponse` makes the same call). A field that
 * does not exist yet is not part of this contract; a client reading this
 * response today learns nothing by its absence, and a later slice adding it
 * is additive, not a breaking change. `streak` arrived that way, in C4.
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
    /** Doc 06 §3.4. About the bond, never about either member: see [StreakResponse]. */
    val streak: TodayStreakResponse,
) {
    companion object {
        fun from(view: TodayView): TodayResponse =
            TodayResponse(
                bondDay = BondDayResponse(date = view.date, status = view.status),
                myEntry = view.myEntry?.let { EntryResponse.of(it, view.date, it.disclosed?.id in view.marked) },
                partnerEntry = view.partnerEntry?.let { PartnerEntryResponse.of(it, view.date, it.disclosed?.id in view.marked) },
                streak = TodayStreakResponse.from(view.streak),
            )
    }
}

/**
 * [TodayResponse.partnerEntry]'s own type — not [myEntry]'s, which stays
 * plain [EntryResponse]`?` because BR-1's first clause makes the locked
 * shape unreachable for a caller's own entry (fix round 1, M1: an earlier
 * version of this KDoc said "shared" between the two fields, which was
 * never true of the code). One of three: [EntryResponse] (BR-1 granted the
 * read, or the reader could read it before it was erased),
 * [LockedEntryResponse] (not yet revealed) or [ErasedEntryResponse] (erased
 * without ever having been revealed).
 *
 * **`sealed`, not merely an interface, and that is load-bearing.** A plain
 * `interface` could be implemented from outside this module by anything —
 * some future type nobody here reviewed, rendered into this exact field
 * with no compiler check on its shape. `sealed` confines every
 * implementation to this module (in fact, this file), so "either revealed
 * in full, locked or erased to exactly BR-8's shape, nothing else" is a closed set
 * the compiler enforces, not a convention a reviewer has to keep re-checking
 * (fix round 1).
 *
 * Jackson serialises each implementation by its own runtime shape without
 * needing `@JsonTypeInfo` here — this type is only ever written to the
 * wire, never read back off it.
 *
 * **In the generated OpenAPI document this is a `oneOf` of the three,
 * discriminated on `status`.** springdoc resolves a sealed interface to its
 * branches by itself; `contracts.OpenApiConfiguration` adds the
 * discriminator. Each wire value names exactly one branch: `LOCKED` is
 * [LockedEntryResponse], `REMOVED` is [ErasedEntryResponse], and the entry's
 * own statuses — `DELETED` included, the wide tombstone — are
 * [EntryResponse]. That is why [ErasedEntryStatus] is not `DELETED`.
 */
internal sealed interface PartnerEntryResponse {
    companion object {
        /**
         * BR-1's answer, as a wire shape — exhaustive over [Readability], so
         * another answer is a compile error here rather than a default nobody
         * chose. Full, or a tombstone the reader could once read:
         * [EntryResponse]. Locked: BR-8's [LockedEntryResponse]. Erased
         * before it was ever revealed: [ErasedEntryResponse]. Both of those
         * are given the author and nothing else — the reading has nothing
         * else to give them. A non-member: nothing.
         *
         * [favourited], the caller's own bookmark, goes to the one branch
         * that has a field for it. The two narrow shapes are not given it
         * and could not carry it: an entry the caller was never shown has
         * nothing of the caller's on it either.
         */
        fun of(
            reading: EntryReading,
            date: LocalDate,
            favourited: Boolean,
        ): PartnerEntryResponse? =
            when (reading.readability) {
                Readability.FULL, Readability.TOMBSTONE -> EntryResponse.of(reading, date, favourited)
                Readability.LOCKED -> reading.authorMemberId?.let { LockedEntryResponse(it) }
                Readability.TOMBSTONE_UNSEEN -> reading.authorMemberId?.let { ErasedEntryResponse(it) }
                Readability.NOT_A_MEMBER -> null
            }
    }
}

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
