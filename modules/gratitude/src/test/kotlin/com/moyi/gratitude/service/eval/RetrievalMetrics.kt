package com.moyi.gratitude.service.eval

/**
 * The eval's arithmetic, and the only place it is done (spec §4: the tool computes none).
 *
 * - **recall@k**: of a query's relevant entries, the share among the first [k] answered.
 * - **reciprocal rank**: 1 / the place of the first relevant entry among the first [k], 0 if none.
 * - **pooled precision**: of every entry answered to every query, the share relevant to the query
 *   it was answered to. A no-relevant query adds only to the denominator: each answer it gets is a
 *   stray day. `null` when nothing was answered at all, which is no evidence either way.
 */
internal object RetrievalMetrics {
    const val K = 10

    fun recallAt(
        k: Int,
        ranked: List<String>,
        relevant: Set<String>,
    ): Double {
        require(relevant.isNotEmpty()) { "recall needs a relevant entry" }
        return ranked.take(k).count { it in relevant }.toDouble() / relevant.size
    }

    fun reciprocalRank(
        k: Int,
        ranked: List<String>,
        relevant: Set<String>,
    ): Double {
        val place = ranked.take(k).indexOfFirst { it in relevant }
        return if (place < 0) 0.0 else 1.0 / (place + 1)
    }

    /** [answers]: per query, what it was answered and what is relevant to it. */
    fun pooledPrecision(answers: List<Pair<Collection<String>, Set<String>>>): Double? {
        val answered = answers.sumOf { (found, _) -> found.size }
        if (answered == 0) return null
        return answers.sumOf { (found, relevant) -> found.count { it in relevant } }.toDouble() / answered
    }

    /** The largest threshold whose precision is known and at least [floor], or `null` if there is none. */
    fun largestThreshold(
        precisionAt: Map<Double, Double?>,
        floor: Double,
    ): Double? = precisionAt.filterValues { it != null && it >= floor }.keys.maxOrNull()
}
