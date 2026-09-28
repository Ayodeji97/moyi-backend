package com.moyi.gratitude.web

import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.service.EntryDraft
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
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
 * [text] carries two edge checks and nothing more. [NOT_ONLY_SPACE] is the
 * blank check ADR-0029 §13 requires be restated here (Java's `\s` and
 * Kotlin's `isBlank` disagree about `U+00A0`); [Size] is the octet backstop
 * FR-041 draws at the edge. The **grapheme count** — the 500-character limit
 * a person actually means — is [EntryText.of]'s alone, in the domain, and is
 * deliberately not restated here: one statement of that rule, not two that
 * could quietly drift apart.
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
 * it resolves to now, in the bond's zone.
 */
internal data class SubmitEntryRequest(
    @field:NotNull
    @field:Pattern(regexp = NOT_ONLY_SPACE, message = "must not be blank")
    @field:Size(max = EntryText.MAX_OCTETS)
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
}
