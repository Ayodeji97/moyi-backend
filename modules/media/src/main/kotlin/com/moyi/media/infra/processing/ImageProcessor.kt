package com.moyi.media.infra.processing

import com.moyi.media.domain.ImageFormat
import com.moyi.media.domain.ImageRefusal
import com.moyi.media.domain.ImageVariant
import java.nio.file.Path

/**
 * Turns an uploaded photo into the images kept of it (spec §4 D2): reads its
 * size from the header and refuses one over the pixel ceiling **before
 * decoding it**, decodes it upright, drops every piece of metadata, and
 * encodes [ImageVariant.FULL] and [ImageVariant.DISPLAY] as WebP within their
 * byte caps. Never hands back the original bytes (T-07: "re-encode every
 * image, never serve the original bytes").
 *
 * Internal, like the object store: the vendor (libvips) is replaceable inside
 * this module. The caller has already checked that the bytes are the ones
 * declared and are one of the allowed formats (`ImageFormat`); this port is
 * not where those rules live, and it does not repeat them.
 *
 * Implemented by [VipsImageProcessor] and by [ProcessingOff], the stand-in
 * production names until the media worker exists (ADR-0048).
 */
internal interface ImageProcessor {
    /** Whether this process makes variants at all. `false` only for [ProcessingOff]. */
    val processesHere: Boolean

    /**
     * The variants of the photo at [source], whose first bytes are [format],
     * written into [workDir], which the caller owns and deletes. A refusal is
     * final: the same bytes would be refused again.
     */
    fun encode(
        source: Path,
        format: ImageFormat,
        workDir: Path,
    ): Encoding
}

/** What [ImageProcessor.encode] made of a photo. */
internal sealed interface Encoding {
    /** Every variant, each within its caps, as files in the work directory. */
    data class Encoded(
        val variants: List<EncodedVariant>,
    ) : Encoding

    /** The photo cannot be made into variants, for [reason]. */
    data class Refused(
        val reason: ImageRefusal,
    ) : Encoding
}

/** One encoded variant: the WebP file, its size in pixels and in bytes. */
internal data class EncodedVariant(
    val variant: ImageVariant,
    val file: Path,
    val width: Int,
    val height: Int,
    val bytes: Long,
)

/**
 * No image is processed in this process. Asked for by name only
 * (`moyi.media.processing.provider: off`), never the default: production's
 * `config/deploy.yml` names it until the media worker, the one process meant to
 * run libvips (owner's ruling Q5), is deployed. With it, `media.process`
 * subscribes to nothing here, so an uploaded photo waits, `UPLOADED`, for a
 * process that does process; nothing is refused and nothing is lost.
 */
internal class ProcessingOff : ImageProcessor {
    override val processesHere: Boolean = false

    override fun encode(
        source: Path,
        format: ImageFormat,
        workDir: Path,
    ): Encoding = error("No image is processed in this process (moyi.media.processing.provider: off).")
}
