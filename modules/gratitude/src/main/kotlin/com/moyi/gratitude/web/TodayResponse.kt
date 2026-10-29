package com.moyi.gratitude.web

import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.EntryReading
import com.moyi.gratitude.domain.ReactionType
import com.moyi.gratitude.domain.Readability
import com.moyi.gratitude.service.PromptView
import com.moyi.gratitude.service.TodayView
import java.time.LocalDate
import java.util.UUID

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
 * the entry has been revealed, or its tombstone if erased after that),
 * [LockedEntryResponse] (not yet revealed), or `null` when there is no partner
 * entry to report: none was written, **or one was erased before it was ever
 * revealed**, which is the same `null` (the owner's ruling of 2026-10-21,
 * `service.shownTo`). **All three are reachable through the API**: a
 * partner's entry is locked until it is revealed, in full after that, its
 * tombstone if its author erases it then, and nothing if they erase it
 * before. None of that is decided here —
 * [PartnerEntryResponse.of] renders whatever
 * [com.moyi.gratitude.domain.Entry.canBeReadBy] answered rather than
 * choosing a shape from the day or the entry itself. Doc 12's
 * reveal-gate test proves exactly this: make the gate answer `FULL` always
 * and a partner's entry stops being a [LockedEntryResponse] and becomes a
 * full [EntryResponse] instead — the failure the gate's own test is written
 * to catch.
 *
 * **[prompt] is the bond's prompt of the day** (FR-047, ADR-0038): the same prompt for
 * both members, each in their own language, and `null` when the bond takes no writes or
 * the library has nothing for it. One `null` for every cause, so it tells a block from a
 * leave no more than anything else here does. Whether the caller answers it is theirs;
 * dismissing it is the client's alone, with no call (the owner's Q-P2).
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
    val prompt: TodayPromptResponse?,
) {
    companion object {
        fun from(view: TodayView): TodayResponse =
            TodayResponse(
                bondDay = BondDayResponse(date = view.date, status = view.status),
                myEntry =
                    view.myEntry?.let {
                        EntryResponse.of(
                            it,
                            view.date,
                            it.disclosed?.id in view.marked,
                            view.reactions[it.disclosed?.id],
                        )
                    },
                partnerEntry =
                    view.partnerEntry?.let {
                        PartnerEntryResponse.of(it, view.date, it.disclosed?.id in view.marked, view.reactions[it.disclosed?.id])
                    },
                streak = TodayStreakResponse.from(view.streak),
                prompt = view.prompt?.let(TodayPromptResponse::from),
            )
    }
}

/** Doc 06:144, `"prompt": {"id": …, "text": …}`. Nothing says which entry answered it, or whether one did. */
internal data class TodayPromptResponse(
    val id: UUID,
    val text: String,
) {
    companion object {
        fun from(view: PromptView): TodayPromptResponse = TodayPromptResponse(view.id.value, view.text)
    }
}

/**
 * [TodayResponse.partnerEntry]'s own type — not [myEntry]'s, which stays
 * plain [EntryResponse]`?` because BR-1's first clause makes the locked
 * shape unreachable for a caller's own entry (fix round 1, M1: an earlier
 * version of this KDoc said "shared" between the two fields, which was
 * never true of the code). One of two: [EntryResponse] (BR-1 granted the
 * read, or the reader could read it before it was erased) or
 * [LockedEntryResponse] (not yet revealed). An entry erased before it was
 * ever revealed is no shape at all: the day leaves it out (`service.shownTo`).
 * Until 2026-10-28 it was a third, `ErasedEntryResponse`
 * (`{authorMemberId, status: REMOVED}`), and the owner's ruling removed it.
 *
 * **`sealed`, not merely an interface, and that is load-bearing.** A plain
 * `interface` could be implemented from outside this module by anything —
 * some future type nobody here reviewed, rendered into this exact field
 * with no compiler check on its shape. `sealed` confines every
 * implementation to this module (in fact, this file), so "either revealed
 * in full or locked to exactly BR-8's shape, nothing else" is a closed set
 * the compiler enforces, not a convention a reviewer has to keep re-checking
 * (fix round 1).
 *
 * Jackson serialises each implementation by its own runtime shape without
 * needing `@JsonTypeInfo` here — this type is only ever written to the
 * wire, never read back off it.
 *
 * **In the generated OpenAPI document this is a `oneOf` of the two,
 * discriminated on `status`.** springdoc resolves a sealed interface to its
 * branches by itself; `contracts.OpenApiConfiguration` adds the
 * discriminator. Each wire value names exactly one branch: `LOCKED` is
 * [LockedEntryResponse], and the entry's own statuses — `DELETED` included,
 * the wide tombstone — are [EntryResponse].
 */
internal sealed interface PartnerEntryResponse {
    companion object {
        /**
         * BR-1's answer, as a wire shape — exhaustive over [Readability], so
         * another answer is a compile error here rather than a default nobody
         * chose. Full, or a tombstone the reader could once read:
         * [EntryResponse]. Locked: BR-8's [LockedEntryResponse], given the
         * author and nothing else — the reading has nothing else to give it.
         * A non-member: nothing.
         *
         * **Erased before it was ever revealed is not answered here**: the
         * day was rendered without it (`service.shownTo`, the one place that
         * rule lives), so no reading of that answer reaches a response. One
         * that did would be a renderer that skipped the rule, and fails
         * loudly rather than choose a shape the ruling took away, or a
         * `null` that would hide the fault and be a second copy of the rule.
         *
         * [favourited], the caller's own bookmark, and [reaction], the one
         * on the entry, go to the one branch that has fields for them. The
         * locked shape is not given either and could not carry one: an entry
         * the caller was never shown has nothing of the caller's on it, and
         * they cannot have reacted to it.
         */
        fun of(
            reading: EntryReading,
            date: LocalDate,
            favourited: Boolean,
            reaction: ReactionType?,
        ): PartnerEntryResponse? =
            when (reading.readability) {
                Readability.FULL, Readability.TOMBSTONE -> EntryResponse.of(reading, date, favourited, reaction)
                Readability.LOCKED -> reading.authorMemberId?.let { LockedEntryResponse(it) }
                Readability.TOMBSTONE_UNSEEN -> error("a day renders an entry erased before it was shown as no entry (service.shownTo)")
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
