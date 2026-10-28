package com.moyi.media.infra.processing

import com.moyi.media.domain.ImageFormat
import com.moyi.media.domain.ImageRefusal
import com.moyi.media.domain.ImageVariant
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The real libvips, on bytes these tests made (T-07): metadata dropped, the
 * pixel ceiling read before any decode, the caps met by lowering quality,
 * every allowed format read. libvips' command-line tools must be on `PATH`
 * (CI installs them; ADR-0048).
 */
internal class VipsImageProcessorTest {
    @TempDir
    lateinit var dir: Path

    private val processor = Vipses.onPath()

    @Test
    fun `a photo with a GPS position and an orientation comes back upright, and with no metadata at all`() {
        val photo = TestImages.jpegWithGps(width = 40, height = 20)
        // The fixture is what it says: the latitude's bytes are in it, under an EXIF header.
        photo.containsBytes("Exif".toByteArray()) shouldBe true
        photo.containsBytes(latitudeBytes()) shouldBe true

        val encoded = processor.encode(file("photo.jpg", photo), dir).shouldBeInstanceOf<Encoding.Encoded>()

        encoded.variants.map { it.variant } shouldContainExactly ImageVariant.entries
        for (variant in encoded.variants) {
            // Orientation 6 turns 40 wide by 20 high into 20 by 40, and the tag goes with the rest.
            (variant.width to variant.height) shouldBe (20 to 40)
            val bytes = Files.readAllBytes(variant.file)
            ImageFormat.of(bytes) shouldBe ImageFormat.WEBP
            // Read the bytes, not a flag: no metadata chunk, no EXIF header, not the latitude.
            webpChunks(bytes).filter { it in METADATA_CHUNKS }.shouldBeEmpty()
            bytes.containsBytes("Exif".toByteArray()) shouldBe false
            bytes.containsBytes(latitudeBytes()) shouldBe false
        }
    }

    @Test
    fun `a decompression bomb is refused from its header, and never decoded`() {
        val bomb = TestImages.pngBomb(30_000, 30_000, dir.resolve("bomb.png"))
        // A few kilobytes that say 900 million pixels.
        (Files.size(bomb) < 1024L * 1024) shouldBe true

        processor.encode(bomb, dir) shouldBe Encoding.Refused(ImageRefusal.TOO_MANY_PIXELS)

        // The decode writes `decoded.v`; it never ran.
        Files.exists(dir.resolve("decoded.v")) shouldBe false
    }

    @Test
    fun `a photo as hard to compress as noise has its quality lowered until each variant fits its cap`() {
        val encoded = processor.encode(file("noise.jpg", TestImages.noiseJpeg(2400, 1800)), dir).shouldBeInstanceOf<Encoding.Encoded>()

        for (variant in encoded.variants) {
            variant.bytes shouldBeLessThanOrEqual variant.variant.maxBytes
            Files.size(variant.file) shouldBe variant.bytes
        }
    }

    @Test
    fun `each variant fits within its edge and is never enlarged`() {
        val big = processor.encode(file("big.jpg", TestImages.jpeg(3000, 1000)), dir).shouldBeInstanceOf<Encoding.Encoded>()
        big.variants.associate { it.variant to (it.width to it.height) } shouldBe
            mapOf(ImageVariant.FULL to (2048 to 683), ImageVariant.DISPLAY to (1280 to 427))

        val small = processor.encode(file("small.png", TestImages.png(300, 200)), dir).shouldBeInstanceOf<Encoding.Encoded>()
        small.variants.map { it.width to it.height }.toSet() shouldBe setOf(300 to 200)
    }

    @Test
    fun `JPEG, PNG, WebP and HEIC are all read`() {
        val inputs =
            mapOf(
                "a.jpg" to TestImages.jpeg(64, 48),
                "a.png" to TestImages.png(64, 48),
                "a.webp" to TestImages.resource("sample.webp"),
                "a.heic" to TestImages.resource("sample.heic"),
            )
        for ((name, bytes) in inputs) {
            val work = Files.createDirectory(dir.resolve("work-$name"))
            processor.encode(file(name, bytes), work).shouldBeInstanceOf<Encoding.Encoded>()
        }
    }

    @Test
    fun `bytes that start like a JPEG and are not one are unreadable`() {
        val broken = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "not really a photo".toByteArray()
        processor.encode(file("broken.jpg", broken), dir) shouldBe Encoding.Refused(ImageRefusal.UNREADABLE)
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `a decoder that never finishes is killed at the time limit`() {
        // `vipsheader` here answers at once and `vips` hangs: the time limit has to end the decode itself.
        val fake =
            Vipses.fake(
                dir.resolve("bin"),
                vipsheader = "printf 'width: 100\\nheight: 100\\n'",
                vips = "echo \$\$ > \"${dir.resolve("vips.pid")}\"; exec sleep 600",
            )
        val processor = VipsImageProcessor(fake.resolve("vips"), fake.resolve("vipsheader"), Sandbox(null), Duration.ofSeconds(2))

        processor.encode(file("photo.jpg", TestImages.jpeg(10, 10)), dir) shouldBe Encoding.Refused(ImageRefusal.TIMED_OUT)

        val pid = Files.readString(dir.resolve("vips.pid")).trim().toLong()
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false) shouldBe false
    }

    @Test
    fun `a program that says it succeeded and wrote no WebP has not`() {
        val fake =
            Vipses.fake(
                dir.resolve("bin"),
                vipsheader = "printf 'width: 100\\nheight: 100\\n'",
                // Every vips call "succeeds"; the save leaves something that is not a WebP.
                vips = "case \"\$1\" in webpsave) printf 'nothing like a webp' > \"\$3\";; *) : > \"\$3\";; esac; exit 0",
            )
        val processor = VipsImageProcessor(fake.resolve("vips"), fake.resolve("vipsheader"), Sandbox(null), Duration.ofSeconds(10))

        processor.encode(file("photo.jpg", TestImages.jpeg(10, 10)), dir) shouldBe Encoding.Refused(ImageRefusal.UNREADABLE)
    }

    private fun file(
        name: String,
        bytes: ByteArray,
    ): Path = dir.resolve(name).also { Files.write(it, bytes) }

    private fun latitudeBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(TestImages.GPS_LATITUDE.size * 4).order(ByteOrder.BIG_ENDIAN)
        TestImages.GPS_LATITUDE.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    internal companion object {
        /** RIFF chunks that carry metadata (the WebP container spec): EXIF, XMP, the ICC profile. */
        val METADATA_CHUNKS = setOf("EXIF", "XMP ", "ICCP")

        /** The four-character code of every chunk in a WebP's RIFF container. */
        fun webpChunks(bytes: ByteArray): List<String> {
            val chunks = mutableListOf<String>()
            var at = 12
            while (at + 8 <= bytes.size) {
                chunks += String(bytes, at, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                at += 8 + size + (size and 1)
            }
            return chunks
        }

        fun ByteArray.containsBytes(needle: ByteArray): Boolean =
            (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
    }
}
