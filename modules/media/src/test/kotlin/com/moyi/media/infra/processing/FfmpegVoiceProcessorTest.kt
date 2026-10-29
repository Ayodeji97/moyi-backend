package com.moyi.media.infra.processing

import com.moyi.media.domain.MediaRefusal
import com.moyi.media.domain.VoiceFormat
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * ffmpeg on hostile input (spec §4 D3, T-07, ADR-0049): each guard the voice
 * processor holds ffmpeg to, shown by a recording that it stops, with the real
 * ffmpeg on `PATH` (CI installs it; on Linux under production's memory limit).
 * Every recording is made by the test ([TestVoices]).
 */
internal class FfmpegVoiceProcessorTest {
    @TempDir
    lateinit var dir: Path

    private val processor = Ffmpegs.onPath()
    private var work: Path = Path.of("")

    @Test
    fun `each container a phone records is transcoded to one channel of Opus at the ruled bitrate, in Ogg`() {
        val recordings =
            mapOf(
                "M4A (iOS)" to (VoiceFormat.MP4 to TestVoices.m4a(dir)),
                "MP4 (Android)" to (VoiceFormat.MP4 to TestVoices.mp4(dir)),
                "3GP with AAC" to (VoiceFormat.THREE_GPP to TestVoices.threeGpAac(dir)),
                "3GP with AMR-NB" to (VoiceFormat.THREE_GPP to TestVoices.threeGpAmr(dir)),
                "Ogg Opus" to (VoiceFormat.OGG to TestVoices.oggOpus(dir)),
                "WebM Opus" to (VoiceFormat.WEBM to TestVoices.webmOpus(dir)),
                "AMR-NB" to (VoiceFormat.AMR to TestVoices.amrNb(2.0)),
                "AMR-WB" to (VoiceFormat.AMR to TestVoices.amrWb(2.0)),
                "AAC in ADTS" to (VoiceFormat.AAC to TestVoices.adts(dir)),
                "WAV" to (VoiceFormat.WAV to TestVoices.wav(2.0)),
            )
        for ((name, recording) in recordings) {
            withClue(name) {
                val (format, bytes) = recording
                // The format the pipeline would read from these bytes is the one named here.
                VoiceFormat.of(bytes.copyOf(VoiceFormat.SNIFF_BYTES)) shouldBe format

                val transcoded = transcode(bytes, format).shouldBeInstanceOf<Transcoding.Transcoded>()

                // Two seconds, give or take what a codec pads (AAC primes about a hundred milliseconds).
                transcoded.durationMs shouldBeInRange 1_900..2_200
                transcoded.bytes shouldBe Files.size(transcoded.file)
                // The result, read by an ffprobe of the test's own, not by the code under test.
                val kept = Ffmpegs.probe(transcoded.file, "stream=codec_name,channels,sample_rate", "format=format_name")
                kept["streams.stream.0.codec_name"] shouldBe "opus"
                kept["streams.stream.0.channels"] shouldBe "1"
                kept["streams.stream.0.sample_rate"] shouldBe "48000"
                kept["format.format_name"] shouldBe "ogg"
                kept.keys.none { it.startsWith("streams.stream.1.") } shouldBe true
            }
        }
    }

    /** Owner's ruling Q4: 24 kbps. A ten-second note is long enough for the average to mean something. */
    @Test
    fun `the Opus is at the ruled bitrate`() {
        val transcoded = transcode(TestVoices.wav(10.0, sampleRate = 16_000), VoiceFormat.WAV).shouldBeInstanceOf<Transcoding.Transcoded>()

        val bitsPerSecond = transcoded.bytes * 8 * 1000 / transcoded.durationMs
        withClue("$bitsPerSecond bit/s") { (bitsPerSecond in 16_000..32_000) shouldBe true }
    }

    /** Owner's ruling Q4: mono, whatever the recorder wrote. */
    @Test
    fun `a stereo recording is kept as one channel`() {
        val stereo = TestVoices.encoded(dir, "wav", "pcm_s16le", 2.0, "-ac", "2")
        Ffmpegs.probe(Files.write(dir.resolve("stereo.wav"), stereo), "stream=channels")["streams.stream.0.channels"] shouldBe "2"

        val transcoded = transcode(stereo, VoiceFormat.WAV).shouldBeInstanceOf<Transcoding.Transcoded>()

        Ffmpegs.probe(transcoded.file, "stream=channels")["streams.stream.0.channels"] shouldBe "1"
    }

    @Test
    fun `a note over 61 seconds is refused by the length its container says, before anything is transcoded`() {
        transcode(TestVoices.wav(62.0), VoiceFormat.WAV) shouldBe Transcoding.Refused(MediaRefusal.TOO_LONG)

        // Never transcoded: nothing was written.
        Files.exists(work.resolve(FfmpegVoiceProcessor.OUTPUT)) shouldBe false
    }

    @Test
    fun `a note of 60 and a half seconds is within the limit and its grace`() {
        transcode(TestVoices.wav(60.5), VoiceFormat.WAV).shouldBeInstanceOf<Transcoding.Transcoded>().durationMs shouldBeInRange
            60_400..60_600
    }

    @Test
    fun `a container that says less than it holds is refused by the length of what it transcodes to`() {
        val understated = TestVoices.understatedM4a(dir, seconds = 90.0, claimedSeconds = 10)
        // ffprobe, of the test's own, believes the headers: the probe before the transcode would pass it.
        val claimed = Ffmpegs.probe(Files.write(dir.resolve("understated.m4a"), understated), "format=duration")
        claimed["format.duration"]!!.toDouble().toLong() shouldBe 10L

        transcode(understated, VoiceFormat.MP4) shouldBe Transcoding.Refused(MediaRefusal.TOO_LONG)
    }

    /**
     * Review of #111: the transcode of a container that says less than it
     * holds stops a second past the limit (`-t`), rather than decoding all of
     * it. What it wrote, read by the test's own ffprobe, is about 62 seconds,
     * not the 90 the file holds.
     */
    @Test
    fun `the transcode of a container that says less than it holds stops a second past the limit`() {
        transcode(TestVoices.understatedM4a(dir, seconds = 90.0, claimedSeconds = 10), VoiceFormat.MP4) shouldBe
            Transcoding.Refused(MediaRefusal.TOO_LONG)

        val written = Ffmpegs.probe(work.resolve(FfmpegVoiceProcessor.OUTPUT), "format=duration")["format.duration"]!!.toDouble()
        withClue("$written s written") { (written in 61.5..62.5) shouldBe true }
    }

    /**
     * Review of #111: an AAC recording of exactly 61 seconds, the limit and
     * its grace, is kept. AAC's priming makes its Opus 62.5 ms longer on
     * ffmpeg 6.1.1 (the worker's), which [VoiceRules.ENCODER_SLACK_MS] allows.
     */
    @Test
    fun `an M4A of exactly 61 seconds is kept`() {
        val m4a = TestVoices.m4a(dir, seconds = 61.0)
        Ffmpegs.probe(Files.write(dir.resolve("limit.m4a"), m4a), "format=duration")["format.duration"] shouldBe "61.000000"

        transcode(m4a, VoiceFormat.MP4).shouldBeInstanceOf<Transcoding.Transcoded>().durationMs shouldBeInRange 61_000..61_100
    }

    /** Review of #111: what is kept names no version of this server's ffmpeg. */
    @Test
    fun `the kept file carries no fingerprint of the build that made it`() {
        val transcoded = transcode(TestVoices.wav(2.0), VoiceFormat.WAV).shouldBeInstanceOf<Transcoding.Transcoded>()

        val bytes = String(Files.readAllBytes(transcoded.file), Charsets.ISO_8859_1)
        withClue(bytes.take(200)) { Regex("Lav[fc][0-9]").containsMatchIn(bytes) shouldBe false }
        Ffmpegs.probe(transcoded.file, "stream_tags=encoder")["streams.stream.0.tags.encoder"] shouldBe "Lavc libopus"
    }

    @Test
    fun `no title, comment or chapter of the upload is kept`() {
        val tagged = TestVoices.m4aWithTagsAndChapters(dir)
        // The marker is there to be removed.
        String(tagged, Charsets.ISO_8859_1).contains(TestVoices.MARKER) shouldBe true

        val transcoded = transcode(tagged, VoiceFormat.MP4).shouldBeInstanceOf<Transcoding.Transcoded>()

        String(Files.readAllBytes(transcoded.file), Charsets.ISO_8859_1).contains(TestVoices.MARKER) shouldBe false
        val kept =
            Ffmpegs
                .tool(
                    dir,
                    "-show_chapters",
                    "-show_entries",
                    "format_tags:stream_tags",
                    "-of",
                    "flat",
                    transcoded.file.toString(),
                    program = "ffprobe",
                )
        withClue(kept) {
            kept.contains("chapters.chapter.") shouldBe false
            kept.contains("title") shouldBe false
            kept.contains("comment") shouldBe false
        }
    }

    @Test
    fun `a recording that carries a picture is refused, and no image decoder is opened for it`() {
        transcode(TestVoices.m4aWithCoverArt(dir), VoiceFormat.MP4) shouldBe Transcoding.Refused(MediaRefusal.UNREADABLE)
    }

    @Test
    fun `a codec its container may not carry from a phone's recorder is refused, its decoder never opened`() {
        transcode(TestVoices.adpcmWav(dir), VoiceFormat.WAV) shouldBe Transcoding.Refused(MediaRefusal.UNREADABLE)
    }

    /**
     * The demuxer is named, never chosen by ffmpeg's probing: bytes that are
     * not the container they were taken for are refused, even where ffmpeg
     * would have found them a demuxer of its own and decoded them with a
     * decoder that is allowed (Opus in WebM read as Ogg, AAC in MP4 read as
     * ADTS).
     */
    @Test
    fun `bytes are read only as the container they were taken for`() {
        transcode(TestVoices.webmOpus(dir), VoiceFormat.OGG) shouldBe Transcoding.Refused(MediaRefusal.UNREADABLE)
        transcode(TestVoices.m4a(dir), VoiceFormat.AAC) shouldBe Transcoding.Refused(MediaRefusal.UNREADABLE)
    }

    /**
     * Files only: given a URL, ffmpeg opens nothing. A local server stands in
     * for the network and counts what reaches it; nothing does.
     */
    @Test
    fun `ffmpeg opens no network address, whatever it is given`() {
        val wav = TestVoices.wav(2.0)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, wav.size.toLong())
            exchange.responseBody.use { it.write(wav) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/voice.wav"

            processor.transcodeInput(url, VoiceFormat.WAV, dir) shouldBe Transcoding.Refused(MediaRefusal.UNREADABLE)

            requests.get() shouldBe 0
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `what would be kept over its byte cap is refused, and little more than the cap is ever written`() {
        val cap = 8L * 1024
        val capped = Ffmpegs.onPath(maxKeptBytes = cap)

        val refused = capped.transcode(Files.write(dir.resolve("upload"), TestVoices.wav(60.0)), VoiceFormat.WAV, dir)

        refused shouldBe Transcoding.Refused(MediaRefusal.CANNOT_FIT)
        // ffmpeg stopped writing at the cap: a page past it at most, not the ~180 KiB the whole note makes.
        Files.size(dir.resolve(FfmpegVoiceProcessor.OUTPUT)) shouldBeLessThan 2 * cap
    }

    @Test
    fun `a transcode that runs past its time limit is killed, and the voice note refused`() {
        val pid = dir.resolve("ffmpeg.pid")
        val fakes =
            Ffmpegs.fake(
                dir.resolve("bin"),
                ffprobe = """printf 'streams.stream.0.codec_name="aac"\nstreams.stream.0.channels=1\nformat.duration="2.0"\n'""",
                ffmpeg = "echo $$ > \"$pid\"; exec sleep 30",
            )
        val slow = FfmpegVoiceProcessor(fakes.resolve("ffmpeg"), fakes.resolve("ffprobe"), Ffmpegs.sandbox, Duration.ofSeconds(2))

        val started = System.nanoTime()
        val outcome = slow.transcode(Files.write(dir.resolve("upload"), TestVoices.wav(2.0)), VoiceFormat.WAV, dir)
        val took = Duration.ofNanos(System.nanoTime() - started)

        outcome shouldBe Transcoding.Refused(MediaRefusal.TIMED_OUT)
        withClue("took $took") { took.toSeconds() shouldBeLessThan 10L }
        val sleeper = Files.readString(pid).trim().toLong()
        ProcessHandle.of(sleeper).map { it.isAlive }.orElse(false) shouldBe false
    }

    /** In a work directory of its own, as the pipeline gives each upload: [work] is the last one. */
    private fun transcode(
        bytes: ByteArray,
        format: VoiceFormat,
    ): Transcoding {
        work = Files.createTempDirectory(dir, "work-")
        return processor.transcode(Files.write(work.resolve("upload"), bytes), format, work)
    }
}
