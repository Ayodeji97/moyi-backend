package com.moyi.media.infra.processing

import com.moyi.media.domain.MediaRefusal
import com.moyi.media.domain.VoiceFormat
import com.moyi.media.domain.VoiceRules
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * ffmpeg (owner's ruling Q5's worker carries it), through its two programs,
 * each run in the [Sandbox] as libvips' are (ADR-0048): a process of its own,
 * a time limit enforced by killing it, a memory limit on Linux, no inherited
 * environment, output to files read back capped. A demuxer or decoder that
 * crashes, loops or allocates without end does so in that process.
 *
 * **ffmpeg reads hostile bytes here, and is held to this** (T-07, spec §6
 * R-E, ADR-0049):
 *
 * - **One demuxer, named** (`-f`): the container's own, from [VoiceFormat],
 *   which the caller has read from the bytes. ffmpeg never chooses one by
 *   probing, so none of its dozens of other demuxers (a playlist that opens
 *   other files, an image sequence, a concatenation) ever reads the upload.
 * - **Named decoders only** (`-codec_whitelist`): the audio codecs that
 *   container may carry from a phone's recorder. Any other stream, a cover
 *   picture or a video track included, has no decoder opened for it, and the
 *   probe fails on it: such a file is refused, never partly decoded.
 * - **Files only** (`-protocol_whitelist file`, and the input given as a
 *   `file:` URL): ffmpeg opens nothing over a network and no other protocol,
 *   whatever the file asks for.
 * - **The length is read before the transcode** (`ffprobe`), and a note over
 *   [VoiceRules.LIMIT_MS] is refused without being decoded. A container can
 *   say less than it holds, so the transcode itself stops one second past the
 *   limit (`-t`) and the result's own length is read again: a note that ran
 *   on is refused too.
 * - **The output is bounded** (`-fs` one byte past [maxKeptBytes], then
 *   checked): a transcode that would write more than may be kept stops
 *   writing and is refused.
 * - **Nothing of the upload but its first audio stream is kept**: `-map
 *   0:a:0`, `-map_metadata -1` (titles, comments, dates, the recorder's own
 *   tags) and `-map_chapters -1`.
 * - **What is kept is Opus**, mono, at [VoiceRules.BITS_PER_SECOND] (Q4), in
 *   Ogg, and is checked to be after it is written: a file whose stream is not
 *   one Opus channel is never kept. The bitrate is constrained VBR, so a
 *   note's average stays near the ruling whatever the sound (unconstrained, a
 *   pure tone came out at 39 kbit/s; constrained, 25), with the `voip`
 *   tuning, which favours speech.
 *
 * Every program shares one [Deadline][Sandbox.Deadline]: the note's whole
 * processing is limited, not each step.
 */
internal class FfmpegVoiceProcessor(
    private val ffmpeg: Path,
    private val ffprobe: Path,
    private val sandbox: Sandbox,
    private val timeout: Duration,
    /** [VoiceRules.MAX_KEPT_BYTES]; a parameter only so a test can show the cap is held. */
    private val maxKeptBytes: Long = VoiceRules.MAX_KEPT_BYTES,
) : VoiceProcessor {
    override val processesHere: Boolean = true

    override fun transcode(
        source: Path,
        format: VoiceFormat,
        workDir: Path,
    ): Transcoding = transcodeInput("file:${source.toAbsolutePath()}", format, workDir)

    /**
     * [transcode], from [input] as ffmpeg names an input. Production passes a
     * `file:` URL of the upload in the work directory, and nothing else; the
     * protocol allow-list is tested by passing it something else.
     *
     * One return per refusal, each where its step is: the order of the steps is the rule.
     */
    @Suppress("ReturnCount")
    internal fun transcodeInput(
        input: String,
        format: VoiceFormat,
        workDir: Path,
    ): Transcoding {
        val deadline = Sandbox.Deadline.after(timeout)
        val reading = Reading.of(format)

        val declared =
            when (val probed = probe(input, reading, workDir, deadline)) {
                is Step.Done -> probed.value
                is Step.Failed -> return Transcoding.Refused(probed.reason)
            }
        if (!VoiceRules.mayKeep(declared.durationMs)) return Transcoding.Refused(MediaRefusal.TOO_LONG)

        val out = workDir.resolve(OUTPUT)
        val transcoded = run(listOf(ffmpeg.toString()) + transcodeArguments(input, reading, out), workDir, deadline)
        if (transcoded != null) return Transcoding.Refused(transcoded)
        if (!Files.isRegularFile(out)) return Transcoding.Refused(MediaRefusal.UNREADABLE)
        val bytes = Files.size(out)
        if (bytes > maxKeptBytes) return Transcoding.Refused(MediaRefusal.CANNOT_FIT)

        val kept =
            when (val probed = probe("file:${out.toAbsolutePath()}", Reading.KEPT, workDir, deadline)) {
                is Step.Done -> probed.value
                is Step.Failed -> return Transcoding.Refused(probed.reason)
            }
        if (kept.codec != OPUS || kept.channels != VoiceRules.CHANNELS) return Transcoding.Refused(MediaRefusal.UNREADABLE)
        if (!VoiceRules.mayKeepTranscoded(kept.durationMs)) return Transcoding.Refused(MediaRefusal.TOO_LONG)
        return Transcoding.Transcoded(out, kept.durationMs.toInt(), bytes)
    }

    private fun transcodeArguments(
        input: String,
        reading: Reading,
        out: Path,
    ): List<String> =
        QUIET + listOf("-nostdin") + reading.arguments + listOf("-threads", "1", "-i", input) +
            listOf("-map", "0:a:0", "-map_metadata", "-1", "-map_chapters", "-1") +
            listOf("-c:a", "libopus", "-b:a", VoiceRules.BITS_PER_SECOND.toString(), "-ac", VoiceRules.CHANNELS.toString()) +
            listOf("-vbr", "constrained", "-application", "voip", "-threads", "1") +
            listOf("-t", seconds(VoiceRules.LIMIT_MS + RUN_ON_MS), "-fs", (maxKeptBytes + 1).toString()) +
            listOf("-f", "ogg", "file:${out.toAbsolutePath()}")

    /**
     * What `ffprobe` reads of [input] through [reading]'s demuxer and decoders:
     * the first audio stream's codec and channels, and the container's length.
     * A file it cannot read that way, a stream whose decoder is not allowed
     * included, or one that says no length, is [MediaRefusal.UNREADABLE].
     */
    private fun probe(
        input: String,
        reading: Reading,
        workDir: Path,
        deadline: Sandbox.Deadline,
    ): Step<Probed> {
        val command =
            listOf(ffprobe.toString()) + QUIET + reading.arguments +
                listOf("-select_streams", "a:0", "-show_entries", "stream=codec_name,channels:format=duration", "-of", "flat", input)
        return when (val outcome = sandbox.run(command, ENVIRONMENT, workDir, deadline)) {
            Sandbox.Outcome.TimedOut -> {
                Step.Failed(MediaRefusal.TIMED_OUT)
            }

            is Sandbox.Outcome.Exited -> {
                outcome.takeIf { it.code == 0 }?.let(::probed)?.let { Step.Done(it) } ?: Step.Failed(MediaRefusal.UNREADABLE)
            }
        }
    }

    /** What a successful probe printed, or null when it left out anything this needs. */
    private fun probed(outcome: Sandbox.Outcome.Exited): Probed? {
        val codec = outcome.field("streams.stream.0.codec_name")
        val channels = outcome.field("streams.stream.0.channels")?.toIntOrNull()
        val duration = outcome.field("format.duration")?.let(::milliseconds)
        return codec?.let { channels?.let { duration?.let { Probed(codec, channels, duration) } } }
    }

    /** Runs one program; null when it exited 0, else why it is a refusal. */
    private fun run(
        command: List<String>,
        workDir: Path,
        deadline: Sandbox.Deadline,
    ): MediaRefusal? =
        when (val outcome = sandbox.run(command, ENVIRONMENT, workDir, deadline)) {
            Sandbox.Outcome.TimedOut -> MediaRefusal.TIMED_OUT
            is Sandbox.Outcome.Exited -> if (outcome.code == 0) null else MediaRefusal.UNREADABLE
        }

    /** `name="value"` or `name=value` from `-of flat`; null when absent. */
    private fun Sandbox.Outcome.Exited.field(name: String): String? =
        output
            .lineSequence()
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?.trim()
            ?.removeSurrounding("\"")

    /** Seconds as ffprobe prints them, to whole milliseconds rounded up; null for `N/A` or anything else not a number. */
    private fun milliseconds(seconds: String): Long? =
        seconds
            .toBigDecimalOrNull()
            ?.movePointRight(MILLIS_DIGITS)
            ?.setScale(0, RoundingMode.CEILING)
            ?.toLong()

    private fun seconds(milliseconds: Long): String = BigDecimal.valueOf(milliseconds).movePointLeft(MILLIS_DIGITS).toPlainString()

    /**
     * How a file is read: its demuxer, by name, and the decoders it may open.
     * The names are ffmpeg's: a demuxer's `-f` name and decoders' own names
     * (`amrnb`, not the codec's `amr_nb`), since `-codec_whitelist` matches
     * the decoder.
     */
    internal enum class Reading(
        private val demuxer: String,
        private val decoders: List<String>,
    ) {
        MOV_AAC("mov", listOf("aac")),
        MOV_SPEECH("mov", listOf("aac", "amrnb", "amrwb")),
        OGG("ogg", listOf("opus", "vorbis")),
        MATROSKA("matroska", listOf("opus", "vorbis")),
        AMR("amr", listOf("amrnb", "amrwb")),
        ADTS("aac", listOf("aac")),
        WAV("wav", listOf("pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_u8")),

        /** The result, read back: Ogg, Opus. */
        KEPT("ogg", listOf("opus")),
        ;

        /** Input options, before the input they apply to. */
        val arguments: List<String>
            get() = listOf("-protocol_whitelist", "file", "-f", demuxer, "-codec_whitelist", decoders.joinToString(","))

        companion object {
            fun of(format: VoiceFormat): Reading =
                when (format) {
                    VoiceFormat.MP4 -> MOV_AAC
                    VoiceFormat.THREE_GPP -> MOV_SPEECH
                    VoiceFormat.OGG -> OGG
                    VoiceFormat.WEBM -> MATROSKA
                    VoiceFormat.AMR -> AMR
                    VoiceFormat.AAC -> ADTS
                    VoiceFormat.WAV -> WAV
                }
        }
    }

    private data class Probed(
        val codec: String,
        val channels: Int,
        val durationMs: Long,
    )

    private sealed interface Step<out T> {
        data class Done<T>(
            val value: T,
        ) : Step<T>

        data class Failed(
            val reason: MediaRefusal,
        ) : Step<Nothing>
    }

    internal companion object {
        /** The kept voice note, in the work directory. */
        const val OUTPUT = "voice.ogg"

        /** How far past the limit the transcode may run, so that a note longer than its container said is seen to be. */
        const val RUN_ON_MS: Long = 1_000

        private const val OPUS = "opus"
        private const val MILLIS_DIGITS = 3

        /** Nothing printed but errors (ffmpeg is also given `-nostdin`, which ffprobe does not take). */
        private val QUIET = listOf("-hide_banner", "-v", "error")

        /** ffmpeg needs nothing from the environment; the sandbox passes PATH alone. */
        private val ENVIRONMENT = emptyMap<String, String>()
    }
}
