package com.moyi.media.infra.processing

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * Whether this process turns uploaded photos into their variants, and with
 * what (ADR-0048). The posture is the storage's (ADR-0047 decision 5) and
 * email's (ADR-0017): **the default is the real processor, and it refuses to
 * start when it cannot run.**
 *
 * [Provider.OFF] has to be asked for by name. Production's `config/deploy.yml`
 * asks, until the media worker the owner ruled for (Q5) is deployed: the API's
 * own image has no libvips, and the worker is the one process meant to decode
 * what users upload.
 *
 * Every value arrives from the environment, as `MOYI_MEDIA_PROCESSING_…`.
 */
@ConfigurationProperties(prefix = "moyi.media.processing")
internal data class MediaProcessingProperties(
    val provider: Provider = Provider.VIPS,
    val vips: Vips = Vips(),
) {
    internal enum class Provider {
        /** libvips' command-line programs, `vips` and `vipsheader`, in [Sandbox]. */
        VIPS,

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
        val timeout: Duration = DEFAULT_TIMEOUT,
        /**
         * The address space each program may use (`ulimit -v`, Linux). Measured
         * 2026-10-28 with libvips 8.15.1 on Ubuntu 24.04: a 48-megapixel HEIC
         * peaks at 383 MB resident and needs about 500 MB of address space; a
         * JPEG of the same size, 81 MB (it is shrunk while it is decoded).
         */
        val memoryLimit: DataSize = DEFAULT_MEMORY_LIMIT,
        /**
         * Whether to start where the OS cannot enforce [memoryLimit] (macOS,
         * for development). Never set in production, which is Linux and
         * enforces it; there it changes nothing.
         */
        val allowUnlimitedMemory: Boolean = false,
    )

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(40)
        val DEFAULT_MEMORY_LIMIT: DataSize = DataSize.ofGigabytes(1)
    }
}
