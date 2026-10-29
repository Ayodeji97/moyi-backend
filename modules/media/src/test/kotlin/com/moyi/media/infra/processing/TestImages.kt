package com.moyi.media.infra.processing

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO

/**
 * Images made by the tests themselves, so that what each one carries is known
 * byte for byte: nothing here was downloaded or copied from a phone.
 */
internal object TestImages {
    /** The latitude written into [jpegWithGps]: 51° 30′ 12.34″ N. Plainly made up, and the bytes the test looks for. */
    val GPS_LATITUDE = intArrayOf(51, 1, 30, 1, 1234, 100)

    fun jpeg(
        width: Int,
        height: Int,
        seed: Long = 1,
    ): ByteArray = encode(gradient(width, height, seed), "jpg")

    fun png(
        width: Int,
        height: Int,
        seed: Long = 1,
    ): ByteArray = encode(gradient(width, height, seed), "png")

    fun gif(
        width: Int,
        height: Int,
    ): ByteArray = encode(gradient(width, height, 1), "gif")

    /** A JPEG of random pixels: as hard to compress as a photo can be, so its variants must shed quality to fit. */
    fun noiseJpeg(
        width: Int,
        height: Int,
    ): ByteArray {
        val random = Random(SEED)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, random.nextInt(RGB))
        return encode(image, "jpg")
    }

    /**
     * A [width] × [height] JPEG whose EXIF says it was taken at [GPS_LATITUDE]
     * (and a longitude), with orientation 6: "rotate 90° clockwise to view".
     * The APP1 segment is written here by hand (big-endian TIFF), right after
     * the start-of-image marker, where EXIF belongs.
     */
    fun jpegWithGps(
        width: Int,
        height: Int,
    ): ByteArray {
        val plain = jpeg(width, height)
        val exif = exifSegment()
        return plain.copyOfRange(0, 2) + exif + plain.copyOfRange(2, plain.size)
    }

    /**
     * A PNG that says it is [width] × [height], one bit a pixel, all black:
     * a few kilobytes on disk, gigabytes when decoded. Its pixels are real (a
     * decoder that ignored the header would decode them all), streamed through
     * a deflater so the test never holds them.
     */
    fun pngBomb(
        width: Int,
        height: Int,
        target: Path,
    ): Path {
        Files.newOutputStream(target).use { out ->
            out.write(PNG_SIGNATURE)
            val header =
                ByteBuffer
                    .allocate(IHDR_LENGTH)
                    .putInt(width)
                    .putInt(height)
                    .put(1)
                    .put(0)
                    .put(0)
                    .put(0)
                    .put(0)
                    .array()
            out.write(chunk("IHDR", header))
            val compressed = ByteArrayOutputStream()
            DeflaterOutputStream(compressed, Deflater(Deflater.BEST_COMPRESSION)).use { deflate ->
                val row = ByteArray(1 + (width + BITS - 1) / BITS) // filter byte 0, then zero bits
                repeat(height) { deflate.write(row) }
            }
            out.write(chunk("IDAT", compressed.toByteArray()))
            out.write(chunk("IEND", ByteArray(0)))
        }
        return target
    }

    fun resource(name: String): ByteArray = checkNotNull(TestImages::class.java.getResourceAsStream("/images/$name")).use { it.readBytes() }

    private fun gradient(
        width: Int,
        height: Int,
        seed: Long,
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val tint = (seed * TINT_STEP).toInt() and BYTE
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = x * BYTE / maxOf(1, width - 1)
                val g = y * BYTE / maxOf(1, height - 1)
                image.setRGB(x, y, (r shl 16) or (g shl 8) or tint)
            }
        }
        return image
    }

    private fun encode(
        image: BufferedImage,
        format: String,
    ): ByteArray = ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) { "no $format writer" } }.toByteArray()

    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(typeBytes) }.apply { update(data) }.value
        return ByteBuffer
            .allocate(CHUNK_OVERHEAD + data.size)
            .putInt(data.size)
            .put(typeBytes)
            .put(data)
            .putInt(crc.toInt())
            .array()
    }

    /** APP1, "Exif\0\0", then a TIFF with IFD0 (orientation, GPS pointer) and a GPS IFD. */
    @Suppress("MagicNumber") // A file format, offset by offset.
    private fun exifSegment(): ByteArray {
        val tiff = ByteBuffer.allocate(140)
        tiff.put("MM".toByteArray()).putShort(42).putInt(8)
        // IFD0 at 8: two entries, then no next IFD. GPS IFD at 8 + 2 + 24 + 4 = 38.
        tiff.putShort(2)
        tiff
            .putShort(0x0112)
            .putShort(3)
            .putInt(1)
            .putShort(6)
            .putShort(0) // Orientation = 6
        tiff
            .putShort(0x8825.toShort())
            .putShort(4)
            .putInt(1)
            .putInt(38) // GPSInfo IFD pointer
        tiff.putInt(0)
        // GPS IFD at 38: four entries, then no next IFD. Rationals from 38 + 2 + 48 + 4 = 92.
        tiff.putShort(4)
        tiff
            .putShort(1)
            .putShort(2)
            .putInt(2)
            .put('N'.code.toByte())
            .put(0)
            .putShort(0) // GPSLatitudeRef
        tiff
            .putShort(2)
            .putShort(5)
            .putInt(3)
            .putInt(92) // GPSLatitude
        tiff
            .putShort(3)
            .putShort(2)
            .putInt(2)
            .put('W'.code.toByte())
            .put(0)
            .putShort(0) // GPSLongitudeRef
        tiff
            .putShort(4)
            .putShort(5)
            .putInt(3)
            .putInt(116) // GPSLongitude
        tiff.putInt(0)
        GPS_LATITUDE.forEach { tiff.putInt(it) }
        intArrayOf(0, 1, 7, 1, 3900, 100).forEach { tiff.putInt(it) }
        val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff.array().copyOf(tiff.position())
        val length = body.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + body
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val IHDR_LENGTH = 13
    private const val CHUNK_OVERHEAD = 12
    private const val BITS = 8
    private const val BYTE = 255
    private const val RGB = 0xFFFFFF + 1
    private const val SEED = 20261028L
    private const val TINT_STEP = 37
}
