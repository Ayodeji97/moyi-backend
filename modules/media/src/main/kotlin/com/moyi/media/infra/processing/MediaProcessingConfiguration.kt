package com.moyi.media.infra.processing

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Chooses the [ImageProcessor] from configuration: one `@Bean` with an
 * exhaustive `when`, as the storage's configuration does and for its reason
 * (ADR-0017 decision 3): a provider added and not wired is a compile error.
 */
@Configuration
@EnableConfigurationProperties(MediaProcessingProperties::class)
internal class MediaProcessingConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun imageProcessor(properties: MediaProcessingProperties): ImageProcessor =
        when (properties.provider) {
            MediaProcessingProperties.Provider.VIPS -> {
                vips(properties.vips)
            }

            MediaProcessingProperties.Provider.OFF -> {
                log.warn("Media processing: OFF. Uploaded photos wait, UPLOADED, for a process that processes them (ADR-0048).")
                ProcessingOff()
            }
        }

    /**
     * The real processor, or a failed start: the programs are found, the memory
     * limit is known to be enforced (or explicitly waived where the OS cannot
     * enforce it), and `vips --version` is run through the same sandbox the
     * photos will be. A process that would decode what users send without its
     * limits never starts.
     */
    private fun vips(settings: MediaProcessingProperties.Vips): VipsImageProcessor {
        val vips = program(settings.binDir, "vips")
        val vipsheader = program(settings.binDir, "vipsheader")
        val limit =
            when {
                Sandbox.enforcesMemoryLimits -> settings.memoryLimit.toBytes()
                settings.allowUnlimitedMemory -> null
                else -> refuse("this OS does not enforce the memory limit (ulimit -v), and allow-unlimited-memory is not set")
            }
        val sandbox = Sandbox(limit)
        val probe = Files.createTempDirectory("moyi-vips-check-")
        try {
            val outcome = sandbox.run(listOf(vips.toString(), "--version"), emptyMap(), probe, Sandbox.Deadline.after(START_CHECK))
            val version = (outcome as? Sandbox.Outcome.Exited)?.takeIf { it.code == 0 }?.output?.trim()
            if (version.isNullOrEmpty()) refuse("`vips --version` did not succeed in the sandbox ($outcome)")
            log.info(
                "Media processing: VIPS ({}), memory limit {}, time limit {}",
                version,
                limit?.let { "$it bytes" } ?: "none (this OS cannot enforce one; allowed for development)",
                settings.timeout,
            )
        } finally {
            probe.toFile().deleteRecursively()
        }
        return VipsImageProcessor(vips, vipsheader, sandbox, settings.timeout)
    }

    private fun program(
        binDir: String?,
        name: String,
    ): Path {
        val candidates =
            if (binDir != null) {
                listOf(Path.of(binDir))
            } else {
                System
                    .getenv("PATH")
                    .orEmpty()
                    .split(java.io.File.pathSeparator)
                    .filter { it.isNotBlank() }
                    .map(Path::of)
            }
        return candidates.map { it.resolve(name) }.firstOrNull { Files.isExecutable(it) }?.toAbsolutePath()
            ?: refuse("`$name` was not found (${binDir?.let { "in $it" } ?: "on PATH"})")
    }

    private fun refuse(why: String): Nothing =
        throw IllegalStateException(
            "moyi.media.processing.provider is VIPS (the default) but $why. Install libvips' command-line tools, or set the " +
                "provider to OFF where no photo may be processed here. Refusing to start rather than decode what users send " +
                "without the limits (ADR-0048).",
        )

    private companion object {
        val START_CHECK: Duration = Duration.ofSeconds(10)
    }
}
