package com.moyi.media.api

import java.net.URI
import java.util.UUID

/**
 * How a voice note on an entry is played to one person (spec §4 D3): to its
 * owner and to anyone else who may read the entry alike, once it is ready;
 * until then it is still processing. Unlike a photo it waits on no moderation
 * verdict: FR-101 and the owner's ruling Q1 name images, and the corpus asks
 * no automated check of voice (ADR-0049).
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
 * A voice note as one person is played it: `{id, status, url?, durationMs?}`.
 * [url] and [durationMs] are present exactly when [status] is
 * [VoiceViewStatus.READY].
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
) {
    override fun toString(): String = "VoiceView(id=$id, status=$status, url=${url?.let { "<redacted>" }}, durationMs=$durationMs)"
}

enum class VoiceViewStatus {
    /** Playable: [VoiceView.url] reaches it. */
    READY,

    /** Not playable yet: not transcoded, or failed. Never says which. */
    PROCESSING,
}
