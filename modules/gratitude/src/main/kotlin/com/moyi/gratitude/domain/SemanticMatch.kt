package com.moyi.gratitude.domain

import com.moyi.ai.api.EmbeddingSpace

/**
 * A search's question by meaning (ADR-0054, spec §3.5): [vector] is `q` as [space]'s model
 * embeds it, and an entry matches when its own vector in that space lies within [maxDistance]
 * (cosine distance, pgvector's `<=>`) of it.
 *
 * Beside [SearchTerms], its keyword twin, because both are what the search's one statement
 * asks of a row (`ArchiveDays.matchingQuery`). **A plain class, not a data class**: equality
 * on a [FloatArray] would be by reference (and detekt's `ArrayInDataClass`). Its `toString`
 * leaves the vector out: it is the query, in another form.
 */
internal class SemanticMatch(
    val space: EmbeddingSpace,
    val vector: FloatArray,
    val maxDistance: Double,
) {
    override fun toString() = "SemanticMatch($space, maxDistance=$maxDistance)"
}
