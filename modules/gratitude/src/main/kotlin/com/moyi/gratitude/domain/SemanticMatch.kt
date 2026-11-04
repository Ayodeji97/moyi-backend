package com.moyi.gratitude.domain

/**
 * Where an entry's vectors in one embedding model's space are kept: gratitude's own name for
 * it, one value per table (V41). `infra`'s `EntryEmbeddings` maps the provider's
 * `EmbeddingSpace` onto it and it onto the table's name, each with an exhaustive `when`, so a
 * new model does not compile until it has a table. **Gratitude's, not `ai`'s**, so that this
 * layer depends on nothing (CLAUDE.md, final review M5; `ArchitectureTest` holds it).
 */
internal enum class VectorTable { OPENAI_1536, VOYAGE_1024 }

/**
 * A search's question by meaning (ADR-0054, spec §3.5): [vector] is `q` as the provider's
 * current model embeds it, and an entry matches when its own vector in [table] lies within
 * [maxDistance] (cosine distance, pgvector's `<=>`) of it.
 *
 * Beside [SearchTerms], its keyword twin, because both are what the search's one statement
 * asks of a row (`ArchiveDays.matchingQuery`). **A plain class, not a data class**: equality
 * on a [FloatArray] would be by reference (and detekt's `ArrayInDataClass`). Its `toString`
 * leaves the vector out: it is the query, in another form.
 */
internal class SemanticMatch(
    val table: VectorTable,
    val vector: FloatArray,
    val maxDistance: Double,
) {
    override fun toString() = "SemanticMatch($table, maxDistance=$maxDistance)"
}
