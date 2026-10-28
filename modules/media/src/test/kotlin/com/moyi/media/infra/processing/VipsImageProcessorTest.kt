package com.moyi.media.infra.processing

import com.moyi.media.domain.ImageFormat
import com.moyi.media.domain.ImageRefusal
import com.moyi.media.domain.ImageVariant
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import kotlin.math.abs

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

        val encoded = processor.encode(file("photo.jpg", photo), ImageFormat.JPEG, dir).shouldBeInstanceOf<Encoding.Encoded>()

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

        processor.encode(bomb, ImageFormat.PNG, dir) shouldBe Encoding.Refused(ImageRefusal.TOO_MANY_PIXELS)

        // The decode writes `decoded.v`; it never ran.
        Files.exists(dir.resolve("decoded.v")) shouldBe false
    }

    @Test
    fun `a photo as hard to compress as noise has its quality lowered until each variant fits its cap`() {
        val encoded =
            processor
                .encode(
                    file("noise.jpg", TestImages.noiseJpeg(2400, 1800)),
                    ImageFormat.JPEG,
                    dir,
                ).shouldBeInstanceOf<Encoding.Encoded>()

        for (variant in encoded.variants) {
            variant.bytes shouldBeLessThanOrEqual variant.variant.maxBytes
            Files.size(variant.file) shouldBe variant.bytes
        }
    }

    @Test
    fun `each variant fits within its edge and is never enlarged`() {
        val big =
            processor
                .encode(
                    file("big.jpg", TestImages.jpeg(3000, 1000)),
                    ImageFormat.JPEG,
                    dir,
                ).shouldBeInstanceOf<Encoding.Encoded>()
        big.variants.associate { it.variant to (it.width to it.height) } shouldBe
            mapOf(ImageVariant.FULL to (2048 to 683), ImageVariant.DISPLAY to (1280 to 427))

        val small =
            processor
                .encode(
                    file("small.png", TestImages.png(300, 200)),
                    ImageFormat.PNG,
                    dir,
                ).shouldBeInstanceOf<Encoding.Encoded>()
        small.variants.map { it.width to it.height }.toSet() shouldBe setOf(300 to 200)
    }

    @Test
    fun `JPEG, PNG, WebP and HEIC are all read`() {
        val inputs =
            mapOf(
                "a.jpg" to (TestImages.jpeg(64, 48) to ImageFormat.JPEG),
                "a.png" to (TestImages.png(64, 48) to ImageFormat.PNG),
                "a.webp" to (TestImages.resource("sample.webp") to ImageFormat.WEBP),
                "a.heic" to (TestImages.resource("sample.heic") to ImageFormat.HEIC),
            )
        for ((name, input) in inputs) {
            val work = Files.createDirectory(dir.resolve("work-$name"))
            processor.encode(file(name, input.first), input.second, work).shouldBeInstanceOf<Encoding.Encoded>()
        }
    }

    /**
     * Review of #108: 64 Mi pixels did not bound memory. A 16-bit RGBA PNG of
     * 4096 × 4096 is 16 megapixels, well inside the pixel ceiling, and decodes
     * to 128 MiB: refused from its header, never decoded.
     */
    @Test
    fun `a 16-bit interlaced PNG within the pixel ceiling and over the decoded-bytes ceiling is refused at the header`() {
        Vipses.tool(dir, "black", "black.v", "4096", "4096", "--bands", "4")
        Vipses.tool(dir, "cast", "black.v", "wide.v", "ushort")
        Vipses.tool(dir, "pngsave", "wide.v", "deep.png", "--interlace", "--bitdepth", "16")
        Files.delete(dir.resolve("black.v"))
        Files.delete(dir.resolve("wide.v"))
        val deep = dir.resolve("deep.png")

        processor.encode(deep, ImageFormat.PNG, dir) shouldBe Encoding.Refused(ImageRefusal.TOO_LARGE_DECODED)

        Files.exists(dir.resolve(VipsImageProcessor.DECODED)) shouldBe false
    }

    /**
     * Review of #108: a phone's Display P3 photo must come out the colour it
     * was, not its P3 numbers read as sRGB. The fixture is a flat sRGB green
     * (0, 163, 83) converted to libvips' built-in P3 profile and saved with
     * that profile embedded, so its stored numbers are P3's (72, 161, 91).
     */
    @Test
    fun `a wide-gamut photo is converted to sRGB before its profile is dropped`() {
        Vipses.tool(dir, "black", "black.v", "64", "48", "--bands", "3")
        Vipses.tool(dir, "linear", "black.v", "green.v", "1 1 1", "0 163 83", "--uchar")
        Vipses.tool(dir, "icc_transform", "green.v", "p3.v", "p3", "--input-profile", "srgb", "--embedded")
        Vipses.tool(dir, "jpegsave", "p3.v", "p3.jpg", "--Q", "95")
        Vipses.tool(dir, "-a", "p3.jpg", program = "vipsheader") shouldContain "icc-profile-data"

        val encoded = processor.encode(dir.resolve("p3.jpg"), ImageFormat.JPEG, dir).shouldBeInstanceOf<Encoding.Encoded>()

        for (variant in encoded.variants) {
            webpChunks(Files.readAllBytes(variant.file)) shouldNotContain "ICCP"
            val pixel =
                Vipses
                    .tool(dir, "getpoint", variant.file.toString(), "10", "10")
                    .trim()
                    .split(Regex("\\s+"))
                    .map { it.toDouble() }
            pixel.zip(listOf(0.0, 163.0, 83.0)).forEach { (got, want) -> abs(got - want) shouldBeLessThanOrEqual 3.0 }
        }
    }

    /** Review of #108: what the upload says of itself goes no further than the decode, intermediate files included. */
    @Test
    fun `the decoded intermediate carries no EXIF and no XMP`() {
        processor.encode(file("photo.jpg", TestImages.jpegWithGps(40, 20)), ImageFormat.JPEG, dir).shouldBeInstanceOf<Encoding.Encoded>()

        val header = Vipses.tool(dir, "-a", VipsImageProcessor.DECODED, program = "vipsheader")
        header shouldContain "width: 20"
        header shouldNotContain "exif-data"
        header shouldNotContain "xmp-data"
    }

    /** Review of #108: `--fail-on error`. Without it libvips decodes what it can of a truncated JPEG, and calls it a photo. */
    @Test
    fun `a truncated JPEG is refused, not decoded as far as it goes`() {
        val whole = TestImages.noiseJpeg(400, 300)
        val truncated = whole.copyOf(whole.size * 6 / 10)

        processor.encode(file("cut.jpg", truncated), ImageFormat.JPEG, dir) shouldBe Encoding.Refused(ImageRefusal.UNREADABLE)
    }

    @Test
    fun `bytes that start like a JPEG and are not one are unreadable`() {
        val broken = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "not really a photo".toByteArray()
        processor.encode(file("broken.jpg", broken), ImageFormat.JPEG, dir) shouldBe Encoding.Refused(ImageRefusal.UNREADABLE)
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `a decoder that never finishes is killed at the time limit`() {
        // `vipsheader` here answers at once and `vips` hangs: the time limit has to end the decode itself.
        val fake =
            Vipses.fake(
                dir.resolve("bin"),
                vipsheader = "printf 'width: 100\\nheight: 100\\nbands: 3\\nformat: uchar\\n'",
                vips = "echo \$\$ > \"${dir.resolve("vips.pid")}\"; exec sleep 600",
            )
        val processor = VipsImageProcessor(fake.resolve("vips"), fake.resolve("vipsheader"), Sandbox(null), Duration.ofSeconds(2))

        processor.encode(file("photo.jpg", TestImages.jpeg(10, 10)), ImageFormat.JPEG, dir) shouldBe
            Encoding.Refused(ImageRefusal.TIMED_OUT)

        val pid = Files.readString(dir.resolve("vips.pid")).trim().toLong()
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false) shouldBe false
    }

    @Test
    fun `a program that says it succeeded and wrote no WebP has not`() {
        val fake =
            Vipses.fake(
                dir.resolve("bin"),
                vipsheader = "printf 'width: 100\\nheight: 100\\nbands: 3\\nformat: uchar\\n'",
                // Every vips call "succeeds"; the save leaves something that is not a WebP.
                vips = "case \"\$1\" in webpsave) printf 'nothing like a webp' > \"\$3\";; *) : > \"\$3\";; esac; exit 0",
            )
        val processor = VipsImageProcessor(fake.resolve("vips"), fake.resolve("vipsheader"), Sandbox(null), Duration.ofSeconds(10))

        processor.encode(file("photo.jpg", TestImages.jpeg(10, 10)), ImageFormat.JPEG, dir) shouldBe
            Encoding.Refused(ImageRefusal.UNREADABLE)
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
