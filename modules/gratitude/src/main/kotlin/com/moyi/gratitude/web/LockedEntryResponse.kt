package com.moyi.gratitude.web

import java.util.UUID

/**
 * BR-8: a locked entry — one [com.moyi.gratitude.domain.Entry.canBeReadBy]
 * has refused the caller — serialises to exactly this, and nothing else.
 *
 * **A distinct type is the security control, not a style choice.** The
 * alternative this rejects is [EntryResponse] with every field but
 * [authorMemberId] nulled out: that shape can *carry* the text, the
 * timestamps, whether there is an image — it just happens to have `null` in
 * those slots today. The day somebody adds a field to [EntryResponse] six
 * months from now — a `hasImage` flag, say — and forgets that a nulled
 * instance of it is also this response, that field leaks on every locked
 * entry, silently, because nothing about the type says it must not. A
 * [LockedEntryResponse] cannot leak that field, because it was never given
 * anywhere to put it: there is no compile error to forget, because there is
 * no path from "add a field to the revealed shape" to "it appears here" at
 * all.
 *
 * [authorMemberId] alone, not the text, not `createdAt`, not `intendedAt`,
 * not a length or a media flag — `RevealGateTest`'s first test is the proof,
 * and doc 12 calls it the single most important test file in the
 * repository. [status] is always [LockedEntryStatus.LOCKED] — the enum has
 * no other value — and this type exists for exactly one case: BR-1 answered
 * `LOCKED`. Its constructor is not private, so that is a convention held by
 * its one call site ([PartnerEntryResponse.of], which builds it from a
 * reading that has only the author to give), not something the type
 * enforces; what the type does enforce is that it has nowhere to put more.
 */
internal data class LockedEntryResponse(
    val authorMemberId: UUID,
    val status: LockedEntryStatus = LockedEntryStatus.LOCKED,
) : PartnerEntryResponse

/**
 * A one-value enum rather than a literal `"LOCKED"` string field, so the
 * wire value reads the same way every other status this module serialises
 * does ([com.moyi.gratitude.domain.BondDayStatus], [com.moyi.gratitude.domain.EntryStatus]) —
 * a JSON string, not a boolean or a hand-typed constant a rename could
 * silently drift from.
 */
internal enum class LockedEntryStatus {
    LOCKED,
}

/**
 * A partner's entry that was deleted or withdrawn **before it was ever
 * revealed** ([com.moyi.gratitude.domain.Readability.TOMBSTONE_UNSEEN]):
 * who wrote it, and that it is gone — and nothing else.
 *
 * The same reasoning as [LockedEntryResponse], for the same reader. While
 * the entry was live this caller was entitled to BR-8's two fields; an
 * erasure does not entitle them to more. [EntryResponse]'s tombstone carries
 * an id, a date and two timestamps — when the partner wrote, which BR-8
 * withholds — so this is a distinct type with nowhere to put them, not that
 * one with fields left out.
 */
internal data class ErasedEntryResponse(
    val authorMemberId: UUID,
    val status: ErasedEntryStatus = ErasedEntryStatus.REMOVED,
) : PartnerEntryResponse

/**
 * One value, for the reason [LockedEntryStatus] is — and **`REMOVED`, not
 * `DELETED`, on purpose.** This is a view literal, as `LOCKED` is; the domain
 * still says [com.moyi.gratitude.domain.EntryStatus.DELETED]. `DELETED` on
 * the wire is already [EntryResponse]'s wide tombstone, and
 * `TodayResponse.partnerEntry` is discriminated on `status` — one value, one
 * schema — which is what a generated client builds its sealed types from
 * (doc 06 §2). `REMOVED` tells this reader nothing they lacked, and does not
 * distinguish a delete from a withdrawal.
 */
internal enum class ErasedEntryStatus {
    REMOVED,
}
