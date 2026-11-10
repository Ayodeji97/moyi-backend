package com.moyi.media.domain

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A voice note's loudness over its length, for the app to draw (issue #165,
 * ADR-0061): [SLICES] levels, each 0 to [MAX_LEVEL], one per equal slice of
 * the note in time.
 *
 * **Measured by the server, from the note as it is kept**, never sent by a
 * phone: there is one source of truth, and nothing for a client to forge.
 *
 * Each level is the slice's **RMS**, not its peak: RMS follows how loud speech
 * sounds, and one click does not fill a slice. The levels are **scaled linearly
 * so that the loudest slice is [MAX_LEVEL]**, unless the whole note is below
 * [SILENCE_RMS] (−60 dBFS): then it is silent, every level 0, and its hiss is
 * not amplified into a shape.
 *
 * Plain, not a `data class`, so that its bytes are compared by content.
 */
internal class Waveform private constructor(
    private val bytes: ByteArray,
) {
    /** The [SLICES] levels, in order, each 0 to [MAX_LEVEL]. */
    val levels: List<Int> get() = bytes.map { it.toInt() and BYTE_MASK }

    /** One byte per level, unsigned: what `media_assets.waveform` holds. */
    fun toBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is Waveform && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "Waveform($levels)"

    companion object {
        /** How many levels a voice note has: the client's ruling on moyi-app #53, and issue #165. */
        const val SLICES = 64

        /** The loudest slice's level. */
        const val MAX_LEVEL = 255

        /**
         * Below this RMS in every slice, a note is silent: −60 dBFS of a 16-bit
         * sample (32 768 × 10^−3). A phone's noise floor in a quiet room sits
         * near it; scaled to 255, such a note would show a loud shape of nothing.
         */
        const val SILENCE_RMS = 32.768

        private const val BYTE_MASK = 0xFF

        /**
         * The waveform of [samples], 16-bit mono PCM, the whole note. Slice `i`
         * holds samples `[i·n/64, (i+1)·n/64)`, so the slices differ by at most
         * one sample; a note of fewer than [SLICES] samples leaves some empty,
         * at 0.
         */
        fun measure(samples: ShortArray): Waveform {
            val n = samples.size.toLong()
            val rms =
                DoubleArray(SLICES) { slice ->
                    val from = (slice * n / SLICES).toInt()
                    val to = ((slice + 1) * n / SLICES).toInt()
                    if (to == from) {
                        0.0
                    } else {
                        var sum = 0.0
                        for (i in from until to) sum += samples[i].toDouble() * samples[i]
                        sqrt(sum / (to - from))
                    }
                }
            val loudest = rms.max()
            val levels =
                if (loudest < SILENCE_RMS) {
                    ByteArray(SLICES)
                } else {
                    ByteArray(SLICES) { (rms[it] / loudest * MAX_LEVEL).roundToInt().coerceIn(0, MAX_LEVEL).toByte() }
                }
            return Waveform(levels)
        }

        /** What [toBytes] wrote; anything but [SLICES] bytes is not a waveform. */
        fun ofStored(bytes: ByteArray): Waveform {
            require(bytes.size == SLICES) { "a waveform is $SLICES bytes, not ${bytes.size}" }
            return Waveform(bytes.copyOf())
        }
    }
}
