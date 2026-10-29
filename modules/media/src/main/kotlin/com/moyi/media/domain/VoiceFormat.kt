package com.moyi.media.domain

/**
 * The containers a voice note may arrive in (FR-043, spec §4 D3), each known by
 * its first bytes (T-07), and the content types each may be declared as. These
 * are what the two platforms' recorders write:
 *
 * - **MPEG-4 audio** (`audio/mp4`, `audio/x-m4a`): an ISO `ftyp` box whose
 *   major brand is `M4A ` (iOS's `AVAudioRecorder`), `mp42` (Android's
 *   `MediaRecorder.OutputFormat.MPEG_4`), `mp41` or `isom`.
 * - **3GPP** (`audio/3gpp`): an `ftyp` box whose major brand is `3gp4`
 *   (Android's `OutputFormat.THREE_GPP`, its default), `3gp5` or `3gp6`.
 * - **Ogg** (`audio/ogg`): `OggS` (Android's `OutputFormat.OGG`, API 29).
 * - **WebM** (`audio/webm`): the EBML magic `1A 45 DF A3` (`OutputFormat.WEBM`).
 * - **AMR** (`audio/amr`): `#!AMR\n` or `#!AMR-WB\n` (`AMR_NB`, `AMR_WB`).
 * - **AAC in ADTS** (`audio/aac`): the ADTS sync word, twelve bits set and the
 *   layer bits clear (`OutputFormat.AAC_ADTS`). The weakest magic of the
 *   seven, which is why the transcoder reads it only as ADTS (ADR-0049).
 * - **WAV** (`audio/wav`, `audio/x-wav`): `RIFF····WAVE` (iOS's linear PCM).
 *
 * `audio/3gpp` and `audio/aac` are D3's additions to D1's list: ADR-0047 owed
 * the check of what Android's recorder writes, and these are two of its
 * output formats.
 *
 * **The allow-list is this enum and nothing else**, as [ImageFormat]'s is for
 * photos: [of] answers `null` for anything else (an MP3, a FLAC, a CAF, a
 * playlist), and the pipeline refuses it before any program reads it. The bytes
 * decide, never the declared type: bytes of one format declared as another are
 * refused too ([agreesWith]).
 */
internal enum class VoiceFormat(
    /** The upload content types this format may be declared as (MediaKind.VOICE's list is these, together). */
    val contentTypes: Set<String>,
) {
    MP4(setOf("audio/mp4", "audio/x-m4a")),
    THREE_GPP(setOf("audio/3gpp")),
    OGG(setOf("audio/ogg")),
    WEBM(setOf("audio/webm")),
    AMR(setOf("audio/amr")),
    AAC(setOf("audio/aac")),
    WAV(setOf("audio/wav", "audio/x-wav")),
    ;

    /** Whether an upload declared as [declaredType] may be these bytes. */
    fun agreesWith(declaredType: String): Boolean = declaredType in contentTypes

    companion object {
        /** How many leading bytes [of] reads. */
        const val SNIFF_BYTES = 16

        private val FTYP = ascii("ftyp")
        private val MP4_BRANDS = setOf("M4A ", "mp42", "mp41", "isom")
        private val THREE_GPP_BRANDS = setOf("3gp4", "3gp5", "3gp6")
        private val OGG_MAGIC = ascii("OggS")
        private val EBML_MAGIC = bytes(0x1A, 0x45, 0xDF, 0xA3)
        private val AMR_NB_MAGIC = ascii("#!AMR\n")
        private val AMR_WB_MAGIC = ascii("#!AMR-WB\n")
        private val RIFF = ascii("RIFF")
        private val WAVE = ascii("WAVE")
        private const val BOX_TYPE_AT = 4
        private const val BRAND_AT = 8
        private const val WAVE_AT = 8
        private const val BRAND_LENGTH = 4
        private const val ADTS_SYNC_HIGH = 0xFF
        private const val ADTS_SYNC_LOW_MASK = 0xF6 // the sync's last four bits and the two layer bits
        private const val ADTS_SYNC_LOW = 0xF0
        private const val BYTE = 0xFF

        /** The format [head] (a file's first bytes) is, or `null` for anything not on the list. */
        fun of(head: ByteArray): VoiceFormat? =
            when {
                head.startsWith(FTYP, at = BOX_TYPE_AT) -> {
                    when (head.brandAt(BRAND_AT)) {
                        in MP4_BRANDS -> MP4
                        in THREE_GPP_BRANDS -> THREE_GPP
                        else -> null
                    }
                }

                head.startsWith(OGG_MAGIC) -> {
                    OGG
                }

                head.startsWith(EBML_MAGIC) -> {
                    WEBM
                }

                head.startsWith(AMR_NB_MAGIC) || head.startsWith(AMR_WB_MAGIC) -> {
                    AMR
                }

                head.startsWith(RIFF) && head.startsWith(WAVE, at = WAVE_AT) -> {
                    WAV
                }

                isAdts(head) -> {
                    AAC
                }

                else -> {
                    null
                }
            }

        private fun isAdts(head: ByteArray): Boolean =
            head.size >= 2 &&
                (head[0].toInt() and BYTE) == ADTS_SYNC_HIGH &&
                (head[1].toInt() and ADTS_SYNC_LOW_MASK) == ADTS_SYNC_LOW

        private fun ByteArray.startsWith(
            prefix: ByteArray,
            at: Int = 0,
        ): Boolean = size >= at + prefix.size && prefix.indices.all { this[at + it] == prefix[it] }

        private fun ByteArray.brandAt(at: Int): String? =
            if (size < at + BRAND_LENGTH) null else String(this, at, BRAND_LENGTH, Charsets.US_ASCII)

        private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    }
}

/**
 * What is kept of a voice note, and how long one may be (FR-043, owner's
 * rulings Q3 and Q4), each written once.
 */
internal object VoiceRules {
    /** Owner's ruling Q3: a voice note is at most 60 seconds. */
    const val MAX_DURATION_MS: Long = 60_000

    /**
     * The grace the spec gives on top (§4 D3: "refuse >60 s (+1 s grace)"):
     * a recorder stopped at 60 s writes a few milliseconds more, and the
     * container's own padding adds some.
     */
    const val GRACE_MS: Long = 1_000

    /** The longest voice note kept: anything over this, by its container or by what it transcodes to, is [MediaRefusal.TOO_LONG]. */
    const val LIMIT_MS: Long = MAX_DURATION_MS + GRACE_MS

    /**
     * Doc 06:186: one voice note ≤500 KB after transcoding, per entry per
     * member; a KB is 1024 bytes here, as in [ImageVariant]. A 61 s note at
     * [BITS_PER_SECOND] is about 190 KiB, so this binds only on a transcode
     * gone wrong.
     */
    const val MAX_KEPT_BYTES: Long = 500L * 1024

    /** Owner's ruling Q4: Opus, mono, 24 kbps. */
    const val BITS_PER_SECOND: Int = 24_000

    /** Owner's ruling Q4: mono. */
    const val CHANNELS: Int = 1

    /** The content type the kept voice note is stored and served under: Ogg Opus (RFC 7845). */
    const val KEPT_CONTENT_TYPE = "audio/ogg"

    /**
     * What the transcode adds to a note's length as its Ogg reports it:
     * Opus's pre-skip, 6.5 ms at 48 kHz (measured on ffmpeg 6.1 and 9.0: a
     * 2.000 s note comes out 2.0065 s), with room to spare. Far less than the
     * second a transcode may run on past [LIMIT_MS], so a note longer than
     * its container said is still seen to be.
     */
    const val ENCODER_SLACK_MS: Long = 20

    /** Whether a voice note this long, in milliseconds, by what its container says, may be kept. Zero or less is no recording. */
    fun mayKeep(durationMs: Long): Boolean = durationMs in 1..LIMIT_MS

    /** Whether a transcoded voice note this long, in milliseconds, may be kept: [mayKeep], allowing [ENCODER_SLACK_MS]. */
    fun mayKeepTranscoded(durationMs: Long): Boolean = durationMs in 1..(LIMIT_MS + ENCODER_SLACK_MS)
}
