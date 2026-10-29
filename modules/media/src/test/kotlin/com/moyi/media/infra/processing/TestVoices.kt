package com.moyi.media.infra.processing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.sin

/**
 * Recordings made by the tests themselves, so that what each one carries is
 * known byte for byte: nothing here was recorded on a phone or downloaded.
 * WAV and AMR are written here by hand; the other containers by the test's own
 * `ffmpeg` ([Ffmpegs.tool]), from a tone, outside the code under test.
 */
internal object TestVoices {
    /** Written into tags and chapters where a test looks for it; plainly made up. */
    const val MARKER = "FAKE-TAG-ZQXJ"

    /** A tone, 16-bit mono PCM at [sampleRate] in a RIFF WAVE, [seconds] long. */
    fun wav(
        seconds: Double,
        sampleRate: Int = 8_000,
    ): ByteArray {
        val samples = (seconds * sampleRate).toInt()
        val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) pcm.putShort((sin(2 * PI * TONE_HZ * i / sampleRate) * AMPLITUDE).toInt().toShort())
        val header =
            ByteBuffer
                .allocate(WAV_HEADER)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray())
                .putInt(WAV_HEADER - 8 + samples * 2)
                .put("WAVEfmt ".toByteArray())
                .putInt(16)
                .putShort(1) // linear PCM
                .putShort(1) // mono
                .putInt(sampleRate)
                .putInt(sampleRate * 2)
                .putShort(2)
                .putShort(16)
                .put("data".toByteArray())
                .putInt(samples * 2)
        return header.array() + pcm.array()
    }

    /**
     * AMR-NB (`#!AMR\n`) of [seconds] in 20 ms frames, each a 12.2 kbit/s
     * frame of zero bits: a valid stream, made without an encoder (neither
     * Ubuntu's ffmpeg nor Homebrew's carries one).
     */
    fun amrNb(seconds: Double): ByteArray = amr("#!AMR\n", AMR_NB_FRAME, seconds)

    /** AMR-WB (`#!AMR-WB\n`), as [amrNb], in 23.85 kbit/s frames. */
    fun amrWb(seconds: Double): ByteArray = amr("#!AMR-WB\n", AMR_WB_FRAME, seconds)

    /**
     * A tone [seconds] long, encoded by the test's ffmpeg with [codec] into
     * [muxer]'s container, with any [extra] output options.
     */
    fun encoded(
        dir: Path,
        muxer: String,
        codec: String,
        seconds: Double,
        vararg extra: String,
    ): ByteArray {
        val out = Files.createTempFile(dir, "voice-", ".$muxer")
        Ffmpegs.tool(
            dir,
            "-y",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=$TONE_HZ:sample_rate=16000:duration=$seconds",
            "-ac",
            "1",
            "-c:a",
            codec,
            *extra,
            "-f",
            muxer,
            out.toString(),
        )
        return Files.readAllBytes(out)
    }

    /** iOS's container: MPEG-4 audio with the `M4A ` brand, AAC. */
    fun m4a(
        dir: Path,
        seconds: Double = 2.0,
    ): ByteArray = encoded(dir, "ipod", "aac", seconds)

    /** Android's `OutputFormat.MPEG_4`: the `mp42` brand, AAC. */
    fun mp4(
        dir: Path,
        seconds: Double = 2.0,
    ): ByteArray = encoded(dir, "mp4", "aac", seconds, "-brand", "mp42")

    /** Android's `OutputFormat.THREE_GPP` with AAC. */
    fun threeGpAac(dir: Path): ByteArray = encoded(dir, "3gp", "aac", 2.0)

    /** Android's `OutputFormat.THREE_GPP` with AMR-NB, its default: [amrNb]'s frames, copied into 3GP. */
    fun threeGpAmr(dir: Path): ByteArray {
        val amr = Files.write(Files.createTempFile(dir, "voice-", ".amr"), amrNb(2.0))
        val out = Files.createTempFile(dir, "voice-", ".3gp")
        Ffmpegs.tool(dir, "-y", "-f", "amr", "-i", amr.toString(), "-c", "copy", "-f", "3gp", out.toString())
        return Files.readAllBytes(out)
    }

    fun oggOpus(dir: Path): ByteArray = encoded(dir, "ogg", "libopus", 2.0)

    fun webmOpus(dir: Path): ByteArray = encoded(dir, "webm", "libopus", 2.0)

    /** Android's `OutputFormat.AAC_ADTS`. */
    fun adts(dir: Path): ByteArray = encoded(dir, "adts", "aac", 2.0)

    /** A WAV whose samples are IMA ADPCM, a codec no phone's voice recorder writes into one. */
    fun adpcmWav(dir: Path): ByteArray = encoded(dir, "wav", "adpcm_ima_wav", 2.0)

    /**
     * An M4A carrying [MARKER] in its title and comment, and a chapter named
     * with it: what a recorder (or anyone) may write into a file besides the
     * sound.
     */
    fun m4aWithTagsAndChapters(dir: Path): ByteArray {
        val plain = Files.write(Files.createTempFile(dir, "voice-", ".m4a"), m4a(dir))
        val metadata =
            Files.writeString(
                Files.createTempFile(dir, "chapters-", ".txt"),
                ";FFMETADATA1\ntitle=$MARKER\ncomment=$MARKER\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=1000\ntitle=$MARKER\n",
            )
        val out = Files.createTempFile(dir, "voice-", ".m4a")
        Ffmpegs.tool(
            dir,
            "-y",
            "-i",
            plain.toString(),
            "-i",
            metadata.toString(),
            "-map",
            "0",
            "-map_metadata",
            "1",
            "-map_chapters",
            "1",
            "-c",
            "copy",
            "-f",
            "ipod",
            out.toString(),
        )
        return Files.readAllBytes(out)
    }

    /** An M4A with a PNG picture attached to it, as a music file carries cover art. */
    fun m4aWithCoverArt(dir: Path): ByteArray {
        val plain = Files.write(Files.createTempFile(dir, "voice-", ".m4a"), m4a(dir))
        val picture = Files.createTempFile(dir, "cover-", ".png")
        Ffmpegs.tool(dir, "-y", "-f", "lavfi", "-i", "color=c=red:s=32x32:d=1", "-frames:v", "1", "-f", "image2", picture.toString())
        val out = Files.createTempFile(dir, "voice-", ".m4a")
        Ffmpegs.tool(
            dir,
            "-y",
            "-i",
            plain.toString(),
            "-i",
            picture.toString(),
            "-map",
            "0:a",
            "-map",
            "1:v",
            "-c",
            "copy",
            "-disposition:v",
            "attached_pic",
            "-f",
            "ipod",
            out.toString(),
        )
        return Files.readAllBytes(out)
    }

    /**
     * An M4A [seconds] long whose movie and media headers (`mvhd`, `mdhd`)
     * say it is [claimedSeconds]: a container that says less than it holds.
     * `ffprobe` believes the headers; the decoder plays every sample.
     */
    fun understatedM4a(
        dir: Path,
        seconds: Double,
        claimedSeconds: Int,
    ): ByteArray {
        val bytes = m4a(dir, seconds)
        for (box in listOf("mvhd", "mdhd")) {
            val at = indexOf(bytes, box.toByteArray())
            check(at >= 0 && bytes[at + 4].toInt() == 0) { "a version-0 $box box" }
            // fourcc, version and flags, creation and modification times, then the timescale and the duration.
            val timescale = ByteBuffer.wrap(bytes, at + TIMESCALE_AT, 4).int
            ByteBuffer.wrap(bytes, at + TIMESCALE_AT + 4, 4).putInt(timescale * claimedSeconds)
        }
        return bytes
    }

    private fun amr(
        magic: String,
        frame: ByteArray,
        seconds: Double,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(magic.toByteArray(Charsets.US_ASCII))
        repeat((seconds * FRAMES_PER_SECOND).toInt()) { out.write(frame) }
        return out.toByteArray()
    }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int = (0..haystack.size - needle.size).firstOrNull { at -> needle.indices.all { haystack[at + it] == needle[it] } } ?: -1

    private const val TONE_HZ = 440
    private const val AMPLITUDE = 8_000.0
    private const val WAV_HEADER = 44
    private const val FRAMES_PER_SECOND = 50
    private const val TIMESCALE_AT = 4 + 4 + 4 + 4

    /** Frame type 7 (12.2 kbit/s), quality bit set, then its 244 bits (31 bytes), all zero. */
    private val AMR_NB_FRAME = byteArrayOf(0x3C) + ByteArray(31)

    /** Frame type 8 (23.85 kbit/s), quality bit set, then its 477 bits (60 bytes), all zero. */
    private val AMR_WB_FRAME = byteArrayOf(0x44) + ByteArray(60)
}
