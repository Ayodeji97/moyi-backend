package com.moyi.media.api

import java.net.URI
import java.util.UUID

/**
 * How a voice note on an entry is played to one person (spec §4 D3): to its
 * owner once it is ready; to anybody else only once it has also passed
 * moderation (FR-061, the owner's ruling; ADR-0049 decision 7), and until then it is still
 * processing to them, with no URL and no length. Fail closed, as a photo is
 * before its moderation verdict.
 *
 * `gratitude` asks only about entries the reader may already read (BR-1 has
 * answered before this is reached); a locked entry is never rendered with a
 * voice note at all (BR-8), and that is `gratitude`'s rule, not this one's.
 */
interface VoiceViews {
    /**
     * Each of [voiceIds] as [viewer] (a user id) is played it, keyed by id. An
     * id that names no asset, or a deleted one, has no entry in the answer.
     */
    fun heardBy(
        viewer: UUID,
        voiceIds: Collection<UUID>,
    ): Map<UUID, VoiceView>
}

/**
 * A voice note as one person is played it: `{id, status, url?, durationMs?, sensitive}`.
 * [url] and [durationMs] are present when [status] is [VoiceViewStatus.READY],
 * and on [VoiceViewStatus.NOT_SHARED] unless it was blocked.
 *
 * [url] is a signed GET of an Ogg Opus file, valid for at least 24 hours,
 * ending on the hour and the same for every request in that hour, as a
 * photo's is (doc 05:169, ADR-0048). It is a bearer credential: never logged,
 * and redacted from [toString].
 */
class VoiceView(
    val id: UUID,
    val status: VoiceViewStatus,
    val url: URI?,
    val durationMs: Int?,
    /** Passed moderation with something found that the client blurs (Q16); false otherwise. */
    val sensitive: Boolean = false,
) {
    override fun toString(): String =
        "VoiceView(id=$id, status=$status, url=${url?.let { "<redacted>" }}, durationMs=$durationMs, sensitive=$sensitive)"
}

enum class VoiceViewStatus {
    /** Playable: [VoiceView.url] reaches it. */
    READY,

    /** Not playable yet: not transcoded, failed, not yet moderated, or held back. Never says which. */
    PROCESSING,

    /** Its owner's only: held back from the partner by moderation (Q10). Playable to them unless blocked. */
    NOT_SHARED,
}
