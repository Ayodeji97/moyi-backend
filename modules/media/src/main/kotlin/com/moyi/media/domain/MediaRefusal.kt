package com.moyi.media.domain

/**
 * Why an upload cannot be made into what is kept of it: a photo's variants
 * (D2) or a voice note's Opus (D3). Every one is final: the same bytes would
 * fail the same way again, so the asset becomes `FAILED` and the delivery is
 * not retried (spec §4 D2: "Unrecoverable input → FAILED"). A storage or
 * database that cannot be reached is not one of these; that is thrown, and the
 * outbox tries again.
 *
 * Named `ImageRefusal` in D2; D3 gave voice notes the same reasons and its own
 * one ([TOO_LONG]) rather than a second list that says most of this again
 * (ADR-0049).
 */
internal enum class MediaRefusal {
    /** Nothing is stored under the upload's key any more. */
    SOURCE_MISSING,

    /** The storage holds more bytes than were declared and signed. */
    LONGER_THAN_DECLARED,

    /** The bytes do not have the SHA-256 that was declared and signed. */
    CHECKSUM_MISMATCH,

    /** The first bytes are none of the formats allowed for the kind ([ImageFormat], [VoiceFormat]). */
    NOT_AN_ALLOWED_FORMAT,

    /** The first bytes are an allowed format, but not the one the upload declared. */
    NOT_THE_DECLARED_FORMAT,

    /** A photo's header says more than [ImageRules.MAX_PIXELS], or no size at all. */
    TOO_MANY_PIXELS,

    /** A photo's header says it decodes to more than [ImageRules.MAX_DECODED_BYTES]. */
    TOO_LARGE_DECODED,

    /** Declared and branded HEIC, but libvips reads another codec in it (an AVIF, say). */
    NOT_HEVC,

    /**
     * A voice note longer than [VoiceRules.LIMIT_MS] (60 s and the second of
     * grace, owner's ruling Q3): by what its container says, read before it is
     * transcoded, or by what the transcode actually made of it.
     */
    TOO_LONG,

    /**
     * [MediaAsset.MAX_ATTEMPTS] decodes began and none finished: an upload that
     * keeps killing the process (or failing after its decode in a way that is
     * thrown) is given up rather than retried for ever. An outage before the
     * decode never counts towards it.
     */
    TOO_MANY_ATTEMPTS,

    /**
     * The decoder could not read it, or stopped at a limit (memory, CPU time).
     * For a voice note also: a codec its container may not carry here, or a
     * duration its container cannot say.
     */
    UNREADABLE,

    /** What would be kept does not fit its byte cap: no photo variant at any quality or size tried, or the voice note. */
    CANNOT_FIT,

    /** The work did not finish within its time limit. */
    TIMED_OUT,
}
