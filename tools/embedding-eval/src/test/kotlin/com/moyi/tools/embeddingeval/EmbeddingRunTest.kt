package com.moyi.tools.embeddingeval

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingSpace
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

internal class EmbeddingRunTest {
    /** Answers each text with a one-element vector of its length, and counts its calls' sizes. */
    private class LengthPort : EmbeddingPort {
        override val space = EmbeddingSpace.VOYAGE_3_5
        val calls = mutableListOf<Int>()

        override fun embed(texts: List<String>): List<FloatArray> {
            calls += texts.size
            return texts.map { floatArrayOf(it.length.toFloat()) }
        }
    }

    private val golden =
        GoldenTexts(
            entries = (1..5).map { GoldenTexts.Keyed("e$it", "x".repeat(it * 4)) },
            queries = listOf(GoldenTexts.Keyed("q1", "abcdefgh"), GoldenTexts.Keyed("q2", "abcd")),
        )

    @Test
    fun `entries go in batches as the sweep sends them, queries one at a time as a search sends them`() {
        val port = LengthPort()

        val run = EmbeddingRun.of(port, golden, batch = 2, ticker = Ticker(1_000_000))

        port.calls shouldBe listOf(2, 2, 1, 1, 1)
        run.vectors.keys shouldBe setOf("e1", "e2", "e3", "e4", "e5", "q1", "q2")
        run.vectors.getValue("e3").toList() shouldBe listOf(12f)
        run.vectors.getValue("q2").toList() shouldBe listOf(4f)
    }

    @Test
    fun `latency is timed per call, and tokens are estimated at four characters each`() {
        // Each call takes one more millisecond than the one before: 1, 2, 3 (batches), 4, 5 (queries).
        val run = EmbeddingRun.of(LengthPort(), golden, batch = 2, ticker = Ticker(1_000_000))

        run.batchMillis shouldBe listOf(1.0, 2.0, 3.0)
        run.queryMillis shouldBe listOf(4.0, 5.0)
        // 60 characters of entries and 12 of queries.
        run.estimatedEntryTokens shouldBe 15
        run.estimatedQueryTokens shouldBe 3
    }

    @Test
    fun `p95 is the nearest rank`() {
        EmbeddingRun.p95((1..100).map { it.toDouble() }.shuffled()) shouldBe 95.0
        EmbeddingRun.p95((1..20).map { it.toDouble() }) shouldBe 19.0
        EmbeddingRun.p95(listOf(7.0)) shouldBe 7.0
    }

    /** A clock whose every call is [step] nanoseconds longer than the one before. */
    private class Ticker(
        private val step: Long,
    ) : () -> Long {
        private var now = 0L
        private var gap = 0L
        private var started = false

        override fun invoke(): Long {
            if (started) {
                gap += step
                now += gap
            }
            started = !started
            return now
        }
    }
}
