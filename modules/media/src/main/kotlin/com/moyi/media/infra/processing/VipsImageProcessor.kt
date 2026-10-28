package com.moyi.media.infra.processing

import com.moyi.media.domain.ImageFormat
import com.moyi.media.domain.ImageRefusal
import com.moyi.media.domain.ImageRules
import com.moyi.media.domain.ImageVariant
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
 * 1. **The header, and the pixel ceiling, before any decode.** `vipsheader`
 *    reads the dimensions libvips itself will decode (a HEIC grid's whole
 *    size, not a tile's), and an image over [ImageRules.MAX_PIXELS] is refused
 *    there: a decompression bomb is never decoded.
 * 2. **One decode of the upload**, `vips thumbnail`: shrunk to fit 2048 px as
 *    it is read (a JPEG is never decoded at full size), turned upright from
 *    its orientation tag, and written to a file of libvips' own format. Steps
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
        workDir: Path,
    ): Encoding {
        val deadline = Sandbox.Deadline.after(timeout)
        val header =
            when (val read = header(source, workDir, deadline, UNTRUSTED)) {
                is Step.Done -> read.value
                is Step.Failed -> return Encoding.Refused(read.reason)
            }
        if (!ImageRules.mayDecode(header.width, header.height)) return Encoding.Refused(ImageRefusal.TOO_MANY_PIXELS)

        val decoded = workDir.resolve("decoded.v")
        val decode =
            run(
                listOf(vips.toString(), "thumbnail", source.toString(), decoded.toString()) +
                    fitting(ImageVariant.FULL.maxEdge) + listOf("--fail-on", "error"),
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
            val resizing = listOf(vips.toString(), "thumbnail", decoded.toString(), sized.toString()) + fitting(edge)
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
                if (!isWebp(out)) return Step.Failed(ImageRefusal.UNREADABLE)
                val bytes = Files.size(out)
                if (bytes <= variant.maxBytes) return Step.Done(EncodedVariant(variant, out, size.width, size.height, bytes))
            }
        }
        return Step.Failed(ImageRefusal.CANNOT_FIT)
    }

    /** `vipsheader -a`'s width and height; anything else it says is not read. */
    private fun header(
        file: Path,
        workDir: Path,
        deadline: Sandbox.Deadline,
        environment: Map<String, String>,
    ): Step<Size> =
        when (val outcome = sandbox.run(listOf(vipsheader.toString(), "-a", file.toString()), environment, workDir, deadline)) {
            Sandbox.Outcome.TimedOut -> {
                Step.Failed(ImageRefusal.TIMED_OUT)
            }

            is Sandbox.Outcome.Exited -> {
                val width = outcome.field("width")
                val height = outcome.field("height")
                if (outcome.code != 0 || width == null || height == null) {
                    Step.Failed(ImageRefusal.UNREADABLE)
                } else {
                    Step.Done(Size(width, height))
                }
            }
        }

    /** Runs one program; null when it exited 0, else why it is a refusal. */
    private fun run(
        command: List<String>,
        workDir: Path,
        deadline: Sandbox.Deadline,
        environment: Map<String, String>,
    ): ImageRefusal? =
        when (val outcome = sandbox.run(command, environment, workDir, deadline)) {
            Sandbox.Outcome.TimedOut -> ImageRefusal.TIMED_OUT
            is Sandbox.Outcome.Exited -> if (outcome.code == 0) null else ImageRefusal.UNREADABLE
        }

    private fun Sandbox.Outcome.Exited.field(name: String): Int? =
        output
            .lineSequence()
            .firstOrNull { it.startsWith("$name: ") }
            ?.substringAfter(": ")
            ?.trim()
            ?.toIntOrNull()

    private fun isWebp(file: Path): Boolean =
        Files.isRegularFile(file) &&
            Files.newInputStream(file).use { ImageFormat.of(it.readNBytes(ImageFormat.SNIFF_BYTES)) } == ImageFormat.WEBP

    /** Fit within an [edge]-pixel square, never enlarged. */
    private fun fitting(edge: Int) = listOf(edge.toString(), "--height", edge.toString(), "--size", "down")

    private data class Size(
        val width: Int,
        val height: Int,
    )

    private sealed interface Step<out T> {
        data class Done<T>(
            val value: T,
        ) : Step<T>

        data class Failed(
            val reason: ImageRefusal,
        ) : Step<Nothing>
    }

    internal companion object {
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
