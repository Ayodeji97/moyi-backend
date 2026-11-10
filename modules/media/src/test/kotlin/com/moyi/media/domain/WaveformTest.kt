package com.moyi.media.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

/** Issue #165, ADR-0061: a voice note's 64 levels, measured from its samples. */
internal class WaveformTest {
    @Test
    fun `there are 64 levels, one per equal slice of the note, and the loudest is 255`() {
        // A tone whose amplitude rises steadily from nothing to full over the note.
        val samples = ShortArray(64 * 100) { (sin(2 * PI * it / 20) * 30_000 * it / (64 * 100)).toInt().toShort() }

        val levels = Waveform.measure(samples).levels

        levels.size shouldBe 64
        levels.max() shouldBe 255
        levels.last() shouldBe 255
        // Rising, slice by slice: each slice is the RMS of its own samples, scaled to the loudest.
        levels.zipWithNext().all { (a, b) -> a <= b } shouldBe true
        (levels.first() < 10) shouldBe true
    }

    @Test
    fun `each level is the slice's RMS, scaled linearly so that the loudest slice is 255`() {
        // Half the note at amplitude 10 000, half at 5 000, as a square wave: the RMS is the amplitude.
        val samples = ShortArray(6_400) { i -> ((if (i < 3_200) 10_000 else 5_000) * if (i % 2 == 0) 1 else -1).toShort() }

        val levels = Waveform.measure(samples).levels

        levels.take(32) shouldContainOnly listOf(255)
        levels.drop(32) shouldContainOnly listOf(128) // 5 000 / 10 000 × 255 = 127.5, rounded half up
    }

    @Test
    fun `a note quieter than -60 dBFS throughout is silent, all zeros, and not amplified into a shape`() {
        val hiss = ShortArray(6_400) { (if (it % 2 == 0) 30 else -30).toShort() }
        Waveform.measure(hiss).levels shouldContainOnly listOf(0)
        Waveform.measure(ShortArray(6_400)).levels shouldContainOnly listOf(0)

        // Just over the floor, it is a note, and its loudest slice is 255.
        val quiet = ShortArray(6_400) { (if (it % 2 == 0) 40 else -40).toShort() }
        Waveform.measure(quiet).levels shouldContainOnly listOf(255)
    }

    @Test
    fun `a note of fewer samples than slices has a level for every slice, the empty ones zero`() {
        val levels = Waveform.measure(shortArrayOf(1_000, -1_000, 1_000)).levels

        levels.size shouldBe 64
        levels.count { it == 255 } shouldBe 3
        levels.count { it == 0 } shouldBe 61
        Waveform.measure(ShortArray(0)).levels shouldContainOnly listOf(0)
    }

    @Test
    fun `the full scale of a 16-bit sample does not overflow the sum`() {
        val samples = ShortArray(61_100 * 8) { Short.MIN_VALUE }
        Waveform.measure(samples).levels shouldContainOnly listOf(255)
    }

    @Test
    fun `stored as 64 bytes and read back as the same levels, 0 to 255`() {
        val waveform = Waveform.measure(ShortArray(6_400) { (it * 5).toShort() })

        Waveform.ofStored(waveform.toBytes()) shouldBe waveform
        Waveform.ofStored(waveform.toBytes()).levels shouldBe waveform.levels
        Waveform.ofStored(ByteArray(64) { -1 }).levels shouldContainOnly listOf(255)
    }

    @Test
    fun `stored bytes of any other length are not a waveform`() {
        shouldThrow<IllegalArgumentException> { Waveform.ofStored(ByteArray(63)) }
        shouldThrow<IllegalArgumentException> { Waveform.ofStored(ByteArray(65)) }
    }
}
