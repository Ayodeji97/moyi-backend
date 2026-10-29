package com.moyi.media.infra.processing

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * Whether this process turns uploads into what is kept of them, and with what
 * (ADR-0048, ADR-0049): photos with libvips, voice notes with ffmpeg, each in
 * the [Sandbox]. The posture is the storage's (ADR-0047 decision 5) and
 * email's (ADR-0017): **the default is the real processing, and it refuses to
 * start when it cannot run.**
 *
 * [Provider.OFF] has to be asked for by name, and turns off both. Production's
 * `config/deploy.yml` asks for every container but the media worker the owner
 * ruled for (Q5), which alone names NATIVE (ADR-0052): the worker is the one
 * process meant to decode what users upload. Since ADR-0052 the API runs the
 * same image, which carries libvips and ffmpeg, so in the API it is that line,
 * and no missing program, that keeps decoding out.
 *
 * Every value arrives from the environment, as `MOYI_MEDIA_PROCESSING_…`.
 */
@ConfigurationProperties(prefix = "moyi.media.processing")
internal data class MediaProcessingProperties(
    val provider: Provider = Provider.NATIVE,
    /**
     * Whether to start where the OS cannot enforce the memory limits
     * ([Vips.memoryLimit], [Ffmpeg.memoryLimit]): macOS, for development.
     * Never set in production, which is Linux and enforces them; there it
     * changes nothing. One setting for both programs: whether this OS may run
     * a decoder unlimited is one decision.
     */
    val allowUnlimitedMemory: Boolean = false,
    val vips: Vips = Vips(),
    val ffmpeg: Ffmpeg = Ffmpeg(),
) {
    internal enum class Provider {
        /**
         * The native programs, each in [Sandbox]: libvips' `vips` and
         * `vipsheader` for photos (ADR-0048), ffmpeg's `ffmpeg` and `ffprobe`
         * for voice notes (ADR-0049). Named `VIPS` until D3 gave it a second
         * program; the worker runs both, so one switch turns on both.
         */
        NATIVE,

        /** No processing in this process; `media.process` subscribes to nothing. Never the default. */
        OFF,
    }

    internal data class Vips(
        /** Where `vips` and `vipsheader` are. Unset: found on `PATH` at start-up. */
        val binDir: String? = null,
        /**
         * The whole of one photo's processing, every program it runs included.
         * Well inside the outbox delivery's minute (ADR-0035), so a photo that
         * runs out of time fails as a photo and not as a delivery.
         */
        val timeout: Duration = DEFAULT_VIPS_TIMEOUT,
        /**
         * The address space each program may use (`ulimit -v`, Linux): a
         * backstop, the second bound on memory after the decoded-bytes ceiling
         * that is the first (`ImageRules.MAX_DECODED_BYTES`). Measured on
         * libvips 8.15.1, Ubuntu 24.04 (ADR-0048 §3): 640 MiB is the least of
         * those tried (512, 576, 640 MiB) under which every format, just under
         * the ceiling, decoded whole; at 576 MiB a HEIC exited 0 with a
         * truncated image and a WebP failed. Address space is not resident
         * memory: the most resident memory measured under it was 283 MiB.
         */
        val memoryLimit: DataSize = DEFAULT_VIPS_MEMORY_LIMIT,
    )

    internal data class Ffmpeg(
        /** Where `ffmpeg` and `ffprobe` are. Unset: found on `PATH` at start-up. */
        val binDir: String? = null,
        /**
         * The whole of one voice note's processing, the probe of the upload,
         * the transcode and the probe of its result together. A 61-second note
         * transcodes in well under a second (ADR-0049); the rest is room for a
         * slow box, still well inside the delivery's minute.
         */
        val timeout: Duration = DEFAULT_FFMPEG_TIMEOUT,
        /**
         * The address space each program may use (`ulimit -v`, Linux).
         * Measured on ffmpeg 6.1.1, Ubuntu 24.04 (ADR-0049): a transcode
         * fails to start its threads at 256 MiB and runs at 384 MiB, with
         * about 55 MB resident. 512 MiB leaves room above the least that
         * worked; nothing ffmpeg is asked to do here grows with the input
         * beyond a 61-second buffer.
         */
        val memoryLimit: DataSize = DEFAULT_FFMPEG_MEMORY_LIMIT,
    )

    private companion object {
        val DEFAULT_VIPS_TIMEOUT: Duration = Duration.ofSeconds(40)
        val DEFAULT_VIPS_MEMORY_LIMIT: DataSize = DataSize.ofMegabytes(640)
        val DEFAULT_FFMPEG_TIMEOUT: Duration = Duration.ofSeconds(30)
        val DEFAULT_FFMPEG_MEMORY_LIMIT: DataSize = DataSize.ofMegabytes(512)
    }
}
