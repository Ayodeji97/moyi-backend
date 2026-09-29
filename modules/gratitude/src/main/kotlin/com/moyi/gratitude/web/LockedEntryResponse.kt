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
 * repository. [status] is [LockedEntryStatus.LOCKED] and nothing else can
 * ever construct one: this type exists for exactly one case.
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
