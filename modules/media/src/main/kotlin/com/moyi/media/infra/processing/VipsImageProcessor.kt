package com.moyi.media.infra.processing

import com.moyi.media.domain.ImageFormat
import com.moyi.media.domain.ImageRules
import com.moyi.media.domain.ImageVariant
import com.moyi.media.domain.MediaRefusal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * libvips (owner's ruling Q5), through its two command-line programs, each run
 * in the [Sandbox]: a process of its own with a time limit and, on Linux, a
 * memory limit. A decoder that crashes, loops or allocates without end does so
 * in that process; this JVM only reads an exit code.
 *
 * **In this order** (spec §4 D2):
 *
 * 1. **The header, and the ceilings, before any decode.** `vipsheader`
 *    reads the dimensions, bands and sample format libvips itself will decode
 *    (a HEIC grid's whole size, not a tile's): an image over
 *    [ImageRules.MAX_PIXELS] or [ImageRules.MAX_DECODED_BYTES] is refused
 *    there, so a decompression bomb is never decoded and no decode can need
 *    more memory than was measured. A HEIC must also hold HEVC
 *    (`heif-compression`), not AV1 under a HEIC brand.
 * 2. **One decode of the upload**, `vips thumbnail`: shrunk to fit 2048 px as
 *    it is read (a JPEG is never decoded at full size), turned upright from
 *    its orientation tag, converted to sRGB from any profile it carries, and
 *    written with no metadata (`[keep=none]`) to a file of libvips' own
 *    format, so the later steps read nothing of the upload but pixels. Steps
 *    1 and 2 are the only ones that read bytes a user sent, and they run with
 *    `VIPS_BLOCK_UNTRUSTED`, so libvips refuses its loaders that are not
 *    meant for untrusted input (ImageMagick, Matlab and the like) whatever the
 *    file claims to be. The caller has already allowed only four formats.
 * 3. **Each variant**, from that decoded image: sized to its edge, then saved
 *    as WebP at falling quality until it fits its byte cap; if even the lowest
 *    quality does not fit, a smaller edge and the qualities again.
 *    **`keep=none` on every save drops all metadata**: EXIF (with its GPS
 *    position), XMP, IPTC and the ICC profile. This is the one place metadata
 *    is removed, and what it writes is what is served.
 *
 * Every program shares one [Deadline][Sandbox.Deadline]: the photo's whole
 * processing is limited, not each step.
 */
internal class VipsImageProcessor(
    private val vips: Path,
    private val vipsheader: Path,
    private val sandbox: Sandbox,
    private val timeout: Duration,
) : ImageProcessor {
    override val processesHere: Boolean = true

    // One return per refusal, each where its step is: the order of the steps is the rule.
    @Suppress("ReturnCount")
    override fun encode(
        source: Path,
        format: ImageFormat,
        workDir: Path,
    ): Encoding {
        val deadline = Sandbox.Deadline.after(timeout)
        val header =
            when (val read = header(source, workDir, deadline, UNTRUSTED)) {
                is Step.Done -> read.value
                is Step.Failed -> return Encoding.Refused(read.reason)
            }
        ImageRules.headerRefusal(header.width, header.height, header.bands, header.format)?.let { return Encoding.Refused(it) }
        // A HEIF container may hold AV1 (AVIF) or other codecs under a HEIC brand: the codec libvips reads decides.
        if (format == ImageFormat.HEIC && header.heifCompression != HEVC) return Encoding.Refused(MediaRefusal.NOT_HEVC)

        val decoded = workDir.resolve(DECODED)
        val decode =
            run(
                listOf(vips.toString(), "thumbnail", source.toString(), noMetadata(decoded)) +
                    fitting(ImageVariant.FULL.maxEdge) + listOf("--fail-on", "error") + TO_SRGB,
                workDir,
                deadline,
                UNTRUSTED,
            )
        if (decode != null) return Encoding.Refused(decode)

        val encoded = mutableListOf<EncodedVariant>()
        for (variant in ImageVariant.entries) {
            when (val made = variant(variant, decoded, workDir, deadline)) {
                is Step.Done -> encoded += made.value
                is Step.Failed -> return Encoding.Refused(made.reason)
            }
        }
        return Encoding.Encoded(encoded)
    }

    /**
     * [variant] of the image in [decoded] (ours, already upright and within
     * 2048 px): each edge of [ImageRules.SHRINK_STEPS] in turn, each quality of
     * [ImageRules.QUALITIES] in turn, until a save fits the cap.
     */
    @Suppress("ReturnCount") // The first fit ends the search; each failed program ends it too.
    private fun variant(
        variant: ImageVariant,
        decoded: Path,
        workDir: Path,
        deadline: Sandbox.Deadline,
    ): Step<EncodedVariant> {
        for (fraction in ImageRules.SHRINK_STEPS) {
            val edge = ImageRules.shrunk(variant.maxEdge, fraction)
            val sized = workDir.resolve("${variant.label}-$edge.v")
            val resizing = listOf(vips.toString(), "thumbnail", decoded.toString(), noMetadata(sized)) + fitting(edge) + TO_SRGB
            val resize = run(resizing, workDir, deadline, TRUSTED)
            if (resize != null) return Step.Failed(resize)
            val size =
                when (val read = header(sized, workDir, deadline, TRUSTED)) {
                    is Step.Done -> read.value
                    is Step.Failed -> return read
                }
            for (quality in ImageRules.QUALITIES) {
                val out = workDir.resolve("${variant.label}-$edge-q$quality.webp")
                val save =
                    run(
                        listOf(vips.toString(), "webpsave", sized.toString(), out.toString(), "--Q", quality.toString(), "--keep", "none"),
                        workDir,
                        deadline,
                        TRUSTED,
                    )
                if (save != null) return Step.Failed(save)
                // What is kept must be a WebP, whatever the program said: one that ran out of
                // memory has been seen to exit 0 having written nothing (libvips 8.15 under a
                // tight `ulimit -v`), and an empty or foreign file is never served.
                if (!isWebp(out)) return Step.Failed(MediaRefusal.UNREADABLE)
                val bytes = Files.size(out)
                if (bytes <= variant.maxBytes) return Step.Done(EncodedVariant(variant, out, size.width, size.height, bytes))
            }
        }
        return Step.Failed(MediaRefusal.CANNOT_FIT)
    }

    /** `vipsheader -a`'s size, bands, sample format and (for a HEIF) codec; anything else it says is not read. */
    private fun header(
        file: Path,
        workDir: Path,
        deadline: Sandbox.Deadline,
        environment: Map<String, String>,
    ): Step<Header> =
        when (val outcome = sandbox.run(listOf(vipsheader.toString(), "-a", file.toString()), environment, workDir, deadline)) {
            Sandbox.Outcome.TimedOut -> {
                Step.Failed(MediaRefusal.TIMED_OUT)
            }

            is Sandbox.Outcome.Exited -> {
                val width = outcome.field("width")?.toIntOrNull()
                val height = outcome.field("height")?.toIntOrNull()
                val bands = outcome.field("bands")?.toIntOrNull()
                val format = outcome.field("format")
                val read = listOf(width, height, bands, format).all { it != null }
                if (outcome.code != 0 || !read) {
                    Step.Failed(MediaRefusal.UNREADABLE)
                } else {
                    Step.Done(Header(width!!, height!!, bands!!, format!!, outcome.field("heif-compression")))
                }
            }
        }

    /** `path[keep=none]`: written with no metadata, so nothing of the upload's EXIF or XMP reaches a later step. */
    private fun noMetadata(path: Path) = "$path[keep=none]"

    /** Runs one program; null when it exited 0, else why it is a refusal. */
    private fun run(
        command: List<String>,
        workDir: Path,
        deadline: Sandbox.Deadline,
        environment: Map<String, String>,
    ): MediaRefusal? =
        when (val outcome = sandbox.run(command, environment, workDir, deadline)) {
            Sandbox.Outcome.TimedOut -> MediaRefusal.TIMED_OUT
            is Sandbox.Outcome.Exited -> if (outcome.code == 0) null else MediaRefusal.UNREADABLE
        }

    private fun Sandbox.Outcome.Exited.field(name: String): String? =
        output
            .lineSequence()
            .firstOrNull { it.startsWith("$name: ") }
            ?.substringAfter(": ")
            ?.trim()

    private fun isWebp(file: Path): Boolean =
        Files.isRegularFile(file) &&
            Files.newInputStream(file).use { ImageFormat.of(it.readNBytes(ImageFormat.SNIFF_BYTES)) } == ImageFormat.WEBP

    /** Fit within an [edge]-pixel square, never enlarged. */
    private fun fitting(edge: Int) = listOf(edge.toString(), "--height", edge.toString(), "--size", "down")

    private data class Header(
        val width: Int,
        val height: Int,
        val bands: Int,
        val format: String,
        val heifCompression: String?,
    )

    private sealed interface Step<out T> {
        data class Done<T>(
            val value: T,
        ) : Step<T>

        data class Failed(
            val reason: MediaRefusal,
        ) : Step<Nothing>
    }

    internal companion object {
        /** The decoded upload, in libvips' own format, in the work directory. */
        const val DECODED = "decoded.v"

        private const val HEVC = "hevc"

        /**
         * Pixels converted to sRGB from whatever profile the photo carries (a
         * phone's Display P3, say) **before** `keep=none` drops that profile:
         * dropped unconverted, wide-gamut colour would be read as sRGB and come
         * out shifted (review of #108). An image with no profile is left as it is.
         */
        private val TO_SRGB = listOf("--export-profile", "srgb")

        /**
         * For a program reading what a user sent. One thread, so memory and CPU
         * are one decode's; untrusted loaders refused.
         */
        private val UNTRUSTED = mapOf("VIPS_CONCURRENCY" to "1", "VIPS_BLOCK_UNTRUSTED" to "1")

        /**
         * For a program reading our own decoded file. libvips counts its own
         * file format among the loaders [UNTRUSTED] blocks, so it cannot be
         * read with that set; nothing of the user's file reaches these programs
         * but its pixels.
         */
        private val TRUSTED = mapOf("VIPS_CONCURRENCY" to "1")
    }
}
