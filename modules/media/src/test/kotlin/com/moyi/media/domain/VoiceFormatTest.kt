package com.moyi.media.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The allow-list of voice containers, read from the bytes and never from a
 * name or a declared type (T-07, spec §4 D3), and the length a voice note may
 * have (owner's ruling Q3).
 */
internal class VoiceFormatTest {
    @Test
    fun `each allowed container is known by its first bytes`() {
        for (brand in listOf("M4A ", "mp42", "mp41", "isom")) {
            VoiceFormat.of(ftyp(brand)) shouldBe VoiceFormat.MP4
        }
        for (brand in listOf("3gp4", "3gp5", "3gp6")) {
            VoiceFormat.of(ftyp(brand)) shouldBe VoiceFormat.THREE_GPP
        }
        VoiceFormat.of("OggS".ascii() + bytes(0, 2, 0, 0)) shouldBe VoiceFormat.OGG
        VoiceFormat.of(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x86, 0x81)) shouldBe VoiceFormat.WEBM
        VoiceFormat.of("#!AMR\n".ascii() + bytes(0x3C)) shouldBe VoiceFormat.AMR
        VoiceFormat.of("#!AMR-WB\n".ascii() + bytes(0x44)) shouldBe VoiceFormat.AMR
        VoiceFormat.of("RIFF".ascii() + bytes(0x24, 0, 0, 0) + "WAVEfmt ".ascii()) shouldBe VoiceFormat.WAV
        // ADTS: twelve sync bits, then MPEG-4 (F1) or MPEG-2 (F9), layer bits clear.
        VoiceFormat.of(bytes(0xFF, 0xF1, 0x50, 0x80)) shouldBe VoiceFormat.AAC
        VoiceFormat.of(bytes(0xFF, 0xF9, 0x50, 0x80)) shouldBe VoiceFormat.AAC
    }

    @Test
    fun `everything else is refused, however it is named`() {
        // MP3, with an ID3 tag and without: an ADTS-like sync, but its layer bits are set.
        VoiceFormat.of("ID3".ascii() + bytes(4, 0, 0, 0, 0, 0)).shouldBeNull()
        VoiceFormat.of(bytes(0xFF, 0xFB, 0x90, 0x64)).shouldBeNull()
        VoiceFormat.of("fLaC".ascii() + bytes(0, 0, 0, 0x22)).shouldBeNull()
        VoiceFormat.of("caff".ascii() + bytes(0, 1, 0, 0)).shouldBeNull()
        VoiceFormat.of("FORM".ascii() + bytes(0, 0, 0, 0) + "AIFF".ascii()).shouldBeNull()
        VoiceFormat.of("RIFF".ascii() + bytes(0x24, 0, 0, 0) + "AVI ".ascii()).shouldBeNull()
        VoiceFormat.of("#EXTM3U\n".ascii()).shouldBeNull()
        VoiceFormat.of("ffconcat version 1.0\n".ascii()).shouldBeNull()
        VoiceFormat.of(bytes(0xFF, 0xD8, 0xFF, 0xE0)).shouldBeNull()
        // An ISO box with a brand no recorder writes: QuickTime, a video, a HEIC.
        for (brand in listOf("qt  ", "M4V ", "heic", "avif", "M4B ")) {
            VoiceFormat.of(ftyp(brand)).shouldBeNull()
        }
        VoiceFormat.of(ByteArray(0)).shouldBeNull()
        VoiceFormat.of(bytes(0xFF)).shouldBeNull()
    }

    @Test
    fun `a container agrees only with the content types it may be declared as`() {
        VoiceFormat.MP4.agreesWith("audio/mp4") shouldBe true
        VoiceFormat.MP4.agreesWith("audio/x-m4a") shouldBe true
        VoiceFormat.MP4.agreesWith("audio/3gpp") shouldBe false
        VoiceFormat.WAV.agreesWith("audio/x-wav") shouldBe true
        VoiceFormat.WAV.agreesWith("audio/mp4") shouldBe false
        VoiceFormat.AAC.agreesWith("audio/mp4") shouldBe false
    }

    /** The intent accepts exactly what the pipeline can read: the one list, not two. */
    @Test
    fun `a voice note may be declared as exactly the containers' content types`() {
        MediaKind.VOICE.contentTypes shouldBe
            setOf("audio/mp4", "audio/x-m4a", "audio/3gpp", "audio/ogg", "audio/webm", "audio/amr", "audio/aac", "audio/wav", "audio/x-wav")
    }

    @Test
    fun `a voice note is kept up to 61 seconds, the ruled 60 and the second of grace, and not a millisecond more`() {
        VoiceRules.mayKeep(1) shouldBe true
        VoiceRules.mayKeep(60_000) shouldBe true
        VoiceRules.mayKeep(61_000) shouldBe true
        VoiceRules.mayKeep(61_001) shouldBe false
        VoiceRules.mayKeep(0) shouldBe false
        // What the transcode makes of it may carry Opus's few milliseconds more, and no second more.
        VoiceRules.mayKeepTranscoded(61_063) shouldBe true
        VoiceRules.mayKeepTranscoded(61_100) shouldBe true
        VoiceRules.mayKeepTranscoded(61_101) shouldBe false
        VoiceRules.mayKeepTranscoded(62_000) shouldBe false
    }

    /** An `ftyp` box: size, type, major brand, minor version 0, one compatible brand (`isom`). */
    private fun ftyp(major: String): ByteArray = bytes(0, 0, 0, 20) + "ftyp$major".ascii() + bytes(0, 0, 0, 0) + "isom".ascii()

    private fun String.ascii() = toByteArray(Charsets.US_ASCII)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
