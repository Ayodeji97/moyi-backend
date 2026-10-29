package com.moyi.media.infra.moderation

import com.moyi.media.domain.Finding

/**
 * The three ports moderation looks through (spec §4 D4, ADR-0051), each our
 * type with its vendor in one class (doc 25:118, ADR-0017), internal like
 * [com.moyi.media.infra.storage.ObjectStore]: nothing outside `media` needs
 * them.
 *
 * Each reports an outage by throwing [ModeratorUnavailableException]: the
 * delivery rolls back and the outbox retries it, for as long as the outage
 * lasts, and the partner keeps seeing "processing" (fail closed for delivery,
 * open for the author). A refusal of the bytes themselves, which the same
 * bytes would meet again, is [Moderated.Refused], never thrown.
 */
internal interface ImageModerator {
    /**
     * Whether photos are moderated in this process at all. `false` only for
     * [NoImageModeration]: then `media.moderate-image` subscribes to nothing, a
     * photo keeps no verdict, and no partner ever sees it, until a process
     * with a moderator subscribes and is handed every photo made before
     * (`StartFrom.BEGINNING`).
     */
    val moderatesHere: Boolean

    /** Who looked, for the record (`moderation_scores`, the audit log's actor). */
    val name: String

    /** The photo's display variant, as a JPEG (what Rekognition reads). */
    fun moderate(jpeg: ByteArray): Moderated
}

/**
 * A list of known child sexual abuse images, matched by hash (Q16: "hash
 * matching against a known-CSAM list plus the classifier"). Asked before the
 * classifier; a match is a [com.moyi.media.domain.ModerationVerdict.BLOCKED]
 * whatever the classifier would say. No vendor is wired yet (ADR-0051
 * decision 3: each needs an application the owner makes).
 */
internal interface KnownImageMatcher {
    /** Which list was consulted, for the record; `none` when no list is. */
    val name: String

    /** Whether [candidate] matches a known image. Throws [ModeratorUnavailableException] for an outage. */
    fun matches(candidate: KnownImageCandidate): Boolean
}

/**
 * What a known-image list may match on (ADR-0051 decision 3), all of it this
 * system has once the original is gone:
 *
 * - [jpeg], the display variant re-encoded as a JPEG: what a **perceptual**
 *   matcher reads (PhotoDNA, Arachnid Shield's perceptual hashes, Safer's
 *   PDQ-style matching), which survive resizing and re-encoding;
 * - [uploadSha256], the SHA-256 (base64) of **the bytes the phone uploaded**,
 *   declared, signed into the upload URL and verified by `media.process`
 *   before the original was deleted: what an **exact** hash list matches, for
 *   a list that carries SHA-256. Lists that carry only MD5 or SHA-1 of known
 *   files would need those computed in `media.process` and kept (owed, when a
 *   vendor that needs them is chosen).
 *
 * Neither is logged.
 */
internal class KnownImageCandidate(
    val jpeg: ByteArray,
    val uploadSha256: String,
) {
    override fun toString(): String = "KnownImageCandidate(<redacted>)"
}

/**
 * Moderation of a voice note (the owner's ruling on FR-061; ADR-0049 decision
 * 7), on the Opus that is kept, after the transcode. No vendor is wired
 * (ADR-0051 decision 5: it needs the owner's ruling on cost and vendor).
 */
internal interface VoiceModerator {
    /** `false` only for [NoVoiceModeration]: a voice note then keeps no verdict, and no partner hears it. */
    val moderatesHere: Boolean

    val name: String

    /** The voice note's Ogg Opus. */
    fun moderate(
        ogg: ByteArray,
        durationMs: Int,
    ): Moderated
}

/** What a moderator made of what it was given. */
internal sealed interface Moderated {
    /**
     * What it found, in this system's categories (none: nothing of concern),
     * and the vendor's model or taxonomy version, if it says one, kept in
     * `moderation_scores` so a verdict can be traced to what gave it.
     */
    data class Found(
        val findings: List<Finding>,
        val modelVersion: String? = null,
    ) : Moderated

    /** It cannot moderate these bytes, and would refuse them again; [why] is a word of ours. */
    data class Refused(
        val why: String,
    ) : Moderated
}

/** A moderator or a list could not be reached, or answered as an outage does. Transient: retried. */
internal class ModeratorUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * No photo is moderated in this process (`provider: none`). Never the default:
 * production's `config/deploy.yml` names it until the owner supplies AWS
 * credentials. Fails closed by construction: with it nothing gets a verdict,
 * so no partner is shown a photo; it does not pass anything.
 */
internal class NoImageModeration : ImageModerator {
    override val moderatesHere: Boolean = false
    override val name: String = "none"

    override fun moderate(jpeg: ByteArray): Moderated =
        error("No photo is moderated in this process (moyi.media.moderation.image.provider: none).")
}

/** No known-image list is consulted: every photo goes to the classifier alone, and the record says `none`. */
internal class NoKnownImageList : KnownImageMatcher {
    override val name: String = "none"

    override fun matches(candidate: KnownImageCandidate): Boolean = false
}

/** No voice note is moderated in this process: none gets a verdict, so no partner hears one (ADR-0049 decision 7). */
internal class NoVoiceModeration : VoiceModerator {
    override val moderatesHere: Boolean = false
    override val name: String = "none"

    override fun moderate(
        ogg: ByteArray,
        durationMs: Int,
    ): Moderated = error("No voice note is moderated in this process (moyi.media.moderation.voice.provider: none).")
}
