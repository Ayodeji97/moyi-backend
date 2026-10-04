package com.moyi.gratitude.web

import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.service.EntryDraft
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/**
 * The wire format of `POST /bonds/{bondId}/entries` (spec §6.2).
 *
 * A separate type from [EntryDraft], for the reason `CreateBondRequest`
 * gives: the wire format is a contract with clients, the draft a contract
 * with the service, and letting a rename of one silently rewrite the other
 * is exactly what a distinct type prevents.
 *
 * [text] carries exactly one edge check: [com.moyi.gratitude.web.ValidEntryText],
 * which runs [EntryText.of] itself and reports whatever it complains about —
 * a NUL character, an unpaired surrogate, over the 8192-octet cap, blank (including a non-breaking
 * space, ADR-0029 §13), or over the 500-grapheme cap, in that factory's own
 * order. The text is stored exactly as sent (ruling P12). Fix round
 * 1 found the previous version of this field — `@Pattern(NOT_ONLY_SPACE)`
 * plus `@Size(max = EntryText.MAX_OCTETS)` — restating FR-041 at the edge
 * and getting it wrong (`@Size` counts UTF-16 characters, not UTF-8 octets,
 * so an over-length body passed the edge and reached the domain as an
 * uncaught `IllegalArgumentException`, a `500` rather than FR-041's own
 * `422`). [ValidEntryText]'s own KDoc has the full account.
 *
 * [imageMediaId] and [voiceMediaId] carry no constraint of their own — a
 * well-formed id is refused all the same, by [com.moyi.gratitude.service.SubmitEntry]
 * (`422 MEDIA_NOT_YET_SUPPORTED`), because "not yet supported" is a fact
 * about this deployment, not about whether the value the caller sent is
 * shaped like a `UUID`.
 *
 * [intendedAt] is BR-3a's offline-draft claim: a client that composed an
 * entry before it could send it may say when. Optional, and trusted only
 * within [com.moyi.gratitude.domain.DayAssignment]'s own limits — absent,
 * it resolves to now, in the bond's zone. A claim ahead of the server's
 * clock is accepted and resolved at the submission instant (ruling P11).
 */
internal data class SubmitEntryRequest(
    @field:NotNull
    @field:ValidEntryText
    val text: String,
    val imageMediaId: UUID? = null,
    val voiceMediaId: UUID? = null,
    val intendedAt: Instant? = null,
) {
    fun toDraft(): EntryDraft =
        EntryDraft(
            text = text,
            imageMediaId = imageMediaId,
            voiceMediaId = voiceMediaId,
            intendedAt = intendedAt,
        )

    /**
     * Never the words (doc 18 §5/§9). A data class prints every property, and
     * this one holds an entry's text as a plain `String` — before
     * [EntryText] has wrapped it in something that refuses to print itself.
     */
    override fun toString(): String =
        "SubmitEntryRequest(text=(redacted), imageMediaId=$imageMediaId, voiceMediaId=$voiceMediaId, intendedAt=$intendedAt)"
}
