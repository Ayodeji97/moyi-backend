package com.moyi.media.infra.processing

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Path

/**
 * The wiring, as the storage's is held (ADR-0047 decision 5): NATIVE is the
 * default and does not start unless libvips and ffmpeg can both run within
 * their limits; the stand-in production names until the media worker exists
 * has to be asked for by name, and turns off both (ADR-0048, ADR-0049).
 */
internal class MediaProcessingConfigurationTest {
    @TempDir
    lateinit var empty: Path

    @TempDir
    lateinit var fakes: Path

    private val runner = ApplicationContextRunner().withUserConfiguration(MediaProcessingConfiguration::class.java)

    @Test
    fun `OFF, asked for by name in lower case as deploy-yml writes it, starts with no libvips and no ffmpeg and processes nothing here`() {
        runner
            .withPropertyValues(
                "moyi.media.processing.provider=off",
                "moyi.media.processing.vips.bin-dir=$empty",
                "moyi.media.processing.ffmpeg.bin-dir=$empty",
            ).run { context ->
                val images = context.getBean(ImageProcessor::class.java)
                images.shouldBeInstanceOf<ProcessingOff>()
                images.processesHere shouldBe false
                val voices = context.getBean(VoiceProcessor::class.java)
                voices.shouldBeInstanceOf<VoiceProcessingOff>()
                voices.processesHere shouldBe false
            }
    }

    @Test
    fun `NATIVE, the default, refuses to start where libvips' programs are not found`() {
        runner
            .withPropertyValues(
                "moyi.media.processing.vips.bin-dir=$empty",
                "moyi.media.processing.ffmpeg.bin-dir=${Ffmpegs.binDir}",
                "moyi.media.processing.allow-unlimited-memory=true",
            ).run { context ->
                val messages = failure(context)
                messages shouldContain "`vips` was not found"
                messages shouldContain "OFF"
            }
    }

    @Test
    fun `NATIVE refuses to start where ffmpeg's programs are not found, libvips or no libvips`() {
        runner
            .withPropertyValues(
                "moyi.media.processing.vips.bin-dir=${Vipses.binDir}",
                "moyi.media.processing.ffmpeg.bin-dir=$empty",
                "moyi.media.processing.allow-unlimited-memory=true",
            ).run { context ->
                val messages = failure(context)
                messages shouldContain "`ffmpeg` was not found"
                messages shouldContain "OFF"
            }
    }

    /** Owner's ruling Q4 names Opus: an ffmpeg that cannot write it would fail every voice note, so it never starts. */
    @Test
    fun `NATIVE refuses to start with an ffmpeg that has no Opus encoder`() {
        Ffmpegs.fake(
            fakes,
            ffprobe = "exit 0",
            ffmpeg =
                """case "$*" in *encoder=libopus*) echo "Codec 'libopus' is not recognized."; exit 0;; """ +
                    """*) echo "ffmpeg version fake";; esac""",
        )
        runner
            .withPropertyValues(
                "moyi.media.processing.vips.bin-dir=${Vipses.binDir}",
                "moyi.media.processing.ffmpeg.bin-dir=$fakes",
                "moyi.media.processing.allow-unlimited-memory=true",
            ).run { context ->
                failure(context) shouldContain "-h encoder=libopus` did not succeed in the sandbox"
            }
    }

    @Test
    fun `NATIVE starts where libvips and ffmpeg run within their limits`() {
        runner
            .withPropertyValues(
                "moyi.media.processing.vips.bin-dir=${Vipses.binDir}",
                "moyi.media.processing.ffmpeg.bin-dir=${Ffmpegs.binDir}",
                "moyi.media.processing.allow-unlimited-memory=true",
            ).run { context ->
                val images = context.getBean(ImageProcessor::class.java)
                images.shouldBeInstanceOf<VipsImageProcessor>()
                images.processesHere shouldBe true
                val voices = context.getBean(VoiceProcessor::class.java)
                voices.shouldBeInstanceOf<FfmpegVoiceProcessor>()
                voices.processesHere shouldBe true
            }
    }

    /** macOS cannot enforce the memory limit, so NATIVE starts there only when told it may run without one. */
    @Test
    @EnabledOnOs(OS.MAC)
    fun `where the OS cannot enforce the memory limit, NATIVE refuses to start unless that is explicitly allowed`() {
        runner
            .withPropertyValues(
                "moyi.media.processing.vips.bin-dir=${Vipses.binDir}",
                "moyi.media.processing.ffmpeg.bin-dir=${Ffmpegs.binDir}",
            ).run { context -> failure(context) shouldContain "does not enforce the memory limit" }
    }

    /** A sandbox whose limit cannot be applied fails the start-up check: the check runs each program the way uploads will be run. */
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `on Linux NATIVE starts with the memory limits applied, and the waiver changes nothing`() {
        runner
            .withPropertyValues(
                "moyi.media.processing.vips.bin-dir=${Vipses.binDir}",
                "moyi.media.processing.ffmpeg.bin-dir=${Ffmpegs.binDir}",
            ).run { context ->
                context.getBean(ImageProcessor::class.java).shouldBeInstanceOf<VipsImageProcessor>()
                context.getBean(VoiceProcessor::class.java).shouldBeInstanceOf<FfmpegVoiceProcessor>()
            }
    }

    private fun failure(context: AssertableApplicationContext): String =
        generateSequence(context.startupFailure) { it.cause }.joinToString { it.message.orEmpty() }
}
