package com.moyi.media.infra.processing

import com.moyi.media.infra.moderation.MediaModerationProperties
import com.moyi.media.infra.storage.MediaStorageProperties
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The moderation copy (ADR-0051 decision 1): the display variant made into a
 * JPEG for Rekognition. What libvips refuses is the file's (a refusal, held
 * back for a person); what runs out of time is the machine's (an outage,
 * retried), and must never become a verdict (review of #113). And the whole
 * of one moderation fits inside the delivery it runs in.
 */
internal class ModerationCopyTest {
    @TempDir
    lateinit var work: Path

    @Test
    fun `the real libvips makes a JPEG of a WebP of ours`() {
        Vipses.tool(work, "black", "black.v", "64", "48", "--bands", "3")
        Vipses.tool(work, "webpsave", "black.v", "display.webp")

        val jpeg = Vipses.onPath().jpegForModeration(work.resolve("display.webp"), work).shouldNotBeNull()

        Files.readAllBytes(jpeg).copyOf(3).toList() shouldBe listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    }

    @Test
    fun `a file libvips refuses is a refusal, null`() {
        val bin = Vipses.fake(work.resolve("bin"), vipsheader = "exit 1", vips = "exit 1")
        Files.write(work.resolve("display.webp"), byteArrayOf(1, 2, 3))

        processor(bin).jpegForModeration(work.resolve("display.webp"), work).shouldBeNull()
    }

    @Test
    fun `a conversion that runs out of time is an outage, thrown, never a refusal`() {
        val bin = Vipses.fake(work.resolve("bin"), vipsheader = "exit 1", vips = "exec sleep 30")
        Files.write(work.resolve("display.webp"), byteArrayOf(1, 2, 3))

        assertThrows<ConversionUnavailableException> { processor(bin).jpegForModeration(work.resolve("display.webp"), work) }
    }

    /**
     * The delivery holds its row lock and a pooled connection for the whole of
     * a moderation, and is refused after 60 s (ADR-0035,
     * `moyi.outbox.delivery-timeout`): the fetch of the display variant, its
     * conversion and the vendor's call, each at its own bound, end well inside it.
     */
    @Test
    fun `fetch, conversion and the vendor's call together fit well inside the delivery's minute`() {
        val fetch = MediaStorageProperties.R2().apiCallTimeout
        val conversion = VipsImageProcessor.MODERATION_CONVERSION_TIMEOUT
        val vendor = MediaModerationProperties.Rekognition().apiCallTimeout

        fetch.plus(conversion).plus(vendor) shouldBe Duration.ofSeconds(28)
        (fetch.plus(conversion).plus(vendor) < DELIVERY_LIMIT.dividedBy(2)) shouldBe true
    }

    private fun processor(bin: Path) =
        VipsImageProcessor(bin.resolve("vips"), bin.resolve("vipsheader"), Vipses.sandbox, Duration.ofSeconds(1))

    private companion object {
        val DELIVERY_LIMIT: Duration = Duration.ofSeconds(60)
    }
}
