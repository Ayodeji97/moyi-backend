package com.moyi.media.infra.processing

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Path

/**
 * The wiring, as the storage's is held (ADR-0047 decision 5): VIPS is the
 * default and does not start unless it can run within its limits; the
 * stand-in production names until the media worker exists has to be asked for
 * by name (ADR-0048).
 */
internal class MediaProcessingConfigurationTest {
    @TempDir
    lateinit var empty: Path

    private val runner = ApplicationContextRunner().withUserConfiguration(MediaProcessingConfiguration::class.java)

    @Test
    fun `OFF, asked for by name in lower case as deploy-yml writes it, starts with no libvips and processes nothing here`() {
        runner
            .withPropertyValues("moyi.media.processing.provider=off", "moyi.media.processing.vips.bin-dir=$empty")
            .run { context ->
                val processor = context.getBean(ImageProcessor::class.java)
                processor.shouldBeInstanceOf<ProcessingOff>()
                processor.processesHere shouldBe false
            }
    }

    @Test
    fun `VIPS, the default, refuses to start where libvips' programs are not found`() {
        runner
            .withPropertyValues("moyi.media.processing.vips.bin-dir=$empty", "moyi.media.processing.vips.allow-unlimited-memory=true")
            .run { context ->
                val messages = generateSequence(context.startupFailure) { it.cause }.joinToString { it.message.orEmpty() }
                messages shouldContain "`vips` was not found"
                messages shouldContain "OFF"
            }
    }

    @Test
    fun `VIPS starts where libvips runs within its limits`() {
        runner
            .withPropertyValues(
                "moyi.media.processing.vips.bin-dir=${Vipses.binDir}",
                "moyi.media.processing.vips.allow-unlimited-memory=true",
            ).run { context ->
                val processor = context.getBean(ImageProcessor::class.java)
                processor.shouldBeInstanceOf<VipsImageProcessor>()
                processor.processesHere shouldBe true
            }
    }

    /** macOS cannot enforce the memory limit, so VIPS starts there only when told it may run without one. */
    @Test
    @EnabledOnOs(OS.MAC)
    fun `where the OS cannot enforce the memory limit, VIPS refuses to start unless that is explicitly allowed`() {
        runner
            .withPropertyValues("moyi.media.processing.vips.bin-dir=${Vipses.binDir}")
            .run { context ->
                val messages = generateSequence(context.startupFailure) { it.cause }.joinToString { it.message.orEmpty() }
                messages shouldContain "does not enforce the memory limit"
            }
    }

    /** A sandbox whose limit cannot be applied fails the start-up check: the check runs `vips` the way photos will be run. */
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `on Linux VIPS starts with the memory limit applied, and the waiver changes nothing`() {
        runner
            .withPropertyValues("moyi.media.processing.vips.bin-dir=${Vipses.binDir}")
            .run { context -> context.getBean(ImageProcessor::class.java).shouldBeInstanceOf<VipsImageProcessor>() }
    }
}
