package com.moyi.tools.embeddingeval

import com.moyi.ai.api.EmbeddingPort
import kotlin.math.ceil

/**
 * One provider's vectors for the whole golden set, and what only a run against the provider can
 * measure: how long each call took, and roughly how many tokens were sent. **No retrieval metric
 * is computed here**: recall, MRR and the threshold are `RetrievalEvalTest`'s alone, on the
 * vectors this writes (a rule lives in one place).
 *
 * Entries are sent [batch] at a time, as the sweep sends them; queries one at a time, as a search
 * sends its `q`, so [queryMillis] is the latency a search waits on.
 *
 * The tokens are an **estimate**, four characters to a token: the port answers vectors only and
 * reports no provider usage (ADR-0054 defers token usage), so the true count is the provider
 * dashboard's.
 */
internal class EmbeddingRun(
    val vectors: Map<String, FloatArray>,
    val batchMillis: List<Double>,
    val queryMillis: List<Double>,
    val estimatedEntryTokens: Long,
    val estimatedQueryTokens: Long,
) {
    companion object {
        /** The sweep's batch (`EmbedPendingEntries.BATCH`). */
        const val SWEEP_BATCH = 64
        private const val CHARACTERS_PER_TOKEN = 4
        private const val NANOS_PER_MILLI = 1_000_000.0
        private const val P95 = 0.95

        fun of(
            port: EmbeddingPort,
            golden: GoldenTexts,
            batch: Int = SWEEP_BATCH,
            ticker: () -> Long = System::nanoTime,
        ): EmbeddingRun {
            val vectors = linkedMapOf<String, FloatArray>()
            val batchMillis = mutableListOf<Double>()
            val queryMillis = mutableListOf<Double>()

            fun send(
                texts: List<GoldenTexts.Keyed>,
                millis: MutableList<Double>,
            ) {
                val started = ticker()
                val answered = port.embed(texts.map { it.text })
                millis += (ticker() - started) / NANOS_PER_MILLI
                texts.zip(answered).forEach { (text, vector) -> vectors[text.key] = vector }
            }
            golden.entries.chunked(batch).forEach { send(it, batchMillis) }
            golden.queries.forEach { send(listOf(it), queryMillis) }
            return EmbeddingRun(
                vectors,
                batchMillis,
                queryMillis,
                estimate(golden.entries),
                estimate(golden.queries),
            )
        }

        /** The nearest-rank 95th percentile of [values]. */
        fun p95(values: List<Double>): Double {
            require(values.isNotEmpty()) { "no values" }
            val sorted = values.sorted()
            return sorted[ceil(P95 * sorted.size).toInt() - 1]
        }

        private fun estimate(texts: List<GoldenTexts.Keyed>): Long = texts.sumOf { it.text.length.toLong() } / CHARACTERS_PER_TOKEN
    }
}
