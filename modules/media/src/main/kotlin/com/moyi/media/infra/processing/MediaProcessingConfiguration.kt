package com.moyi.media.infra.processing

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Chooses the [ImageProcessor] and the [VoiceProcessor] from configuration:
 * one `@Bean` each with an exhaustive `when`, as the storage's configuration
 * does and for its reason (ADR-0017 decision 3): a provider added and not wired
 * is a compile error. One switch chooses both (ADR-0049): the worker runs both
 * programs, and production's API neither.
 */
@Configuration
@EnableConfigurationProperties(MediaProcessingProperties::class)
internal class MediaProcessingConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun imageProcessor(properties: MediaProcessingProperties): ImageProcessor =
        when (properties.provider) {
            MediaProcessingProperties.Provider.NATIVE -> {
                vips(properties)
            }

            MediaProcessingProperties.Provider.OFF -> {
                log.warn("Media processing: OFF. Uploaded photos wait, UPLOADED, for a process that processes them (ADR-0048).")
                ProcessingOff()
            }
        }

    @Bean
    fun voiceProcessor(properties: MediaProcessingProperties): VoiceProcessor =
        when (properties.provider) {
            MediaProcessingProperties.Provider.NATIVE -> {
                ffmpeg(properties)
            }

            MediaProcessingProperties.Provider.OFF -> {
                log.warn("Voice processing: OFF. Uploaded voice notes wait, UPLOADED, for a process that transcodes them (ADR-0049).")
                VoiceProcessingOff()
            }
        }

    /**
     * The real image processor, or a failed start: the programs are found, the
     * memory limit is known to be enforced (or explicitly waived where the OS
     * cannot enforce it), and `vips --version` is run through the same sandbox
     * the photos will be. A process that would decode what users send without
     * its limits never starts.
     */
    private fun vips(properties: MediaProcessingProperties): VipsImageProcessor {
        val settings = properties.vips
        val vips = program(settings.binDir, "vips", "libvips' command-line tools")
        val vipsheader = program(settings.binDir, "vipsheader", "libvips' command-line tools")
        val sandbox = sandbox(settings.memoryLimit, properties.allowUnlimitedMemory)
        val version = checked(sandbox, listOf(vips.toString(), "--version")) { it.isNotEmpty() }
        log.info("Media processing: NATIVE, libvips ({}), {}, time limit {}", version, limitOf(sandbox), settings.timeout)
        return VipsImageProcessor(vips, vipsheader, sandbox, settings.timeout)
    }

    /**
     * The real voice processor, or a failed start, as [vips]: both programs
     * found, the memory limit enforced or waived, and ffmpeg run through the
     * sandbox the voice notes will be, once for its version and once to show
     * it has the Opus encoder the owner's ruling Q4 names (`libopus`): an
     * ffmpeg built without it would fail every voice note.
     */
    private fun ffmpeg(properties: MediaProcessingProperties): FfmpegVoiceProcessor {
        val settings = properties.ffmpeg
        val ffmpeg = program(settings.binDir, "ffmpeg", "ffmpeg")
        val ffprobe = program(settings.binDir, "ffprobe", "ffmpeg")
        val sandbox = sandbox(settings.memoryLimit, properties.allowUnlimitedMemory)
        val version = checked(sandbox, listOf(ffmpeg.toString(), "-hide_banner", "-version")) { it.isNotEmpty() }.lineSequence().first()
        checked(sandbox, listOf(ffmpeg.toString(), "-hide_banner", "-h", "encoder=libopus")) { "Encoder libopus" in it }
        log.info("Voice processing: NATIVE, {}, {}, time limit {}", version, limitOf(sandbox), settings.timeout)
        return FfmpegVoiceProcessor(ffmpeg, ffprobe, sandbox, settings.timeout)
    }

    private fun sandbox(
        memoryLimit: DataSize,
        allowUnlimitedMemory: Boolean,
    ): Sandbox =
        Sandbox(
            when {
                Sandbox.enforcesMemoryLimits -> memoryLimit.toBytes()
                allowUnlimitedMemory -> null
                else -> refuse("this OS does not enforce the memory limit (ulimit -v), and allow-unlimited-memory is not set")
            },
        )

    /** Runs [command] in [sandbox] as the uploads will be; its trimmed output, or a failed start unless it exits 0 and [accepts] it. */
    private fun checked(
        sandbox: Sandbox,
        command: List<String>,
        accepts: (String) -> Boolean,
    ): String {
        val probe = Files.createTempDirectory("moyi-media-check-")
        try {
            val outcome = sandbox.run(command, emptyMap(), probe, Sandbox.Deadline.after(START_CHECK))
            val output = (outcome as? Sandbox.Outcome.Exited)?.takeIf { it.code == 0 }?.output?.trim()
            if (output == null ||
                !accepts(output)
            ) {
                refuse("`${Path.of(command.first()).fileName} ${command.drop(1).joinToString(" ")}` did not succeed in the sandbox")
            }
            return output
        } finally {
            probe.toFile().deleteRecursively()
        }
    }

    private fun limitOf(sandbox: Sandbox): String =
        sandbox.memoryLimitBytes?.let { "memory limit $it bytes" }
            ?: "no memory limit (this OS cannot enforce one; allowed for development)"

    private fun program(
        binDir: String?,
        name: String,
        what: String,
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
            ?: refuse("`$name` was not found (${binDir?.let { "in $it" } ?: "on PATH"}); install $what")
    }

    private fun refuse(why: String): Nothing =
        throw IllegalStateException(
            "moyi.media.processing.provider is NATIVE (the default) but $why. Install libvips' command-line tools and ffmpeg, or " +
                "set the provider to OFF where nothing may be processed here. Refusing to start rather than decode what users send " +
                "without the limits (ADR-0048, ADR-0049).",
        )

    private companion object {
        val START_CHECK: Duration = Duration.ofSeconds(10)
    }
}
