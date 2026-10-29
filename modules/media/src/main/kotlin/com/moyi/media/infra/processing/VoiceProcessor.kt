package com.moyi.media.infra.processing

import com.moyi.media.domain.MediaRefusal
import com.moyi.media.domain.VoiceFormat
import java.nio.file.Path

/**
 * Turns an uploaded voice note into what is kept of it (spec §4 D3, owner's
 * rulings Q3 and Q4): its length read and refused over the limit **before it
 * is transcoded**, then one Opus, mono, at the ruled bitrate, with no metadata,
 * within the kept-bytes cap. Never hands back the original bytes.
 *
 * Internal, like [ImageProcessor]: the vendor (ffmpeg) is replaceable inside
 * this module. The caller has already checked that the bytes are the ones
 * declared and one of the allowed containers ([VoiceFormat]); this port does
 * not repeat those rules.
 *
 * Implemented by [FfmpegVoiceProcessor] and by [VoiceProcessingOff], the
 * stand-in production names until the media worker exists (ADR-0049).
 */
internal interface VoiceProcessor {
    /** Whether this process transcodes voice notes at all. `false` only for [VoiceProcessingOff]. */
    val processesHere: Boolean

    /**
     * The Opus made of the voice note at [source], whose first bytes are
     * [format], written into [workDir], which the caller owns and deletes. A
     * refusal is final: the same bytes would be refused again.
     */
    fun transcode(
        source: Path,
        format: VoiceFormat,
        workDir: Path,
    ): Transcoding
}

/** What [VoiceProcessor.transcode] made of a voice note. */
internal sealed interface Transcoding {
    /** The Ogg Opus file in the work directory, its length as transcoded, and its size. */
    data class Transcoded(
        val file: Path,
        val durationMs: Int,
        val bytes: Long,
    ) : Transcoding

    /** The voice note cannot be kept, for [reason]. */
    data class Refused(
        val reason: MediaRefusal,
    ) : Transcoding
}

/**
 * No voice note is transcoded in this process: [ProcessingOff]'s twin, chosen
 * with it by the same `moyi.media.processing.provider: off` (ADR-0049). An
 * uploaded voice note waits, `UPLOADED`, for a process that does transcode.
 */
internal class VoiceProcessingOff : VoiceProcessor {
    override val processesHere: Boolean = false

    override fun transcode(
        source: Path,
        format: VoiceFormat,
        workDir: Path,
    ): Transcoding = error("No voice note is transcoded in this process (moyi.media.processing.provider: off).")
}
