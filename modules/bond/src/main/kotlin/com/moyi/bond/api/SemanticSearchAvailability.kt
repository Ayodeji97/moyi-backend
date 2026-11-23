package com.moyi.bond.api

/**
 * Whether a bond whose own setting is on is searched by meaning **now** (ADR-0070): the half of
 * `BondResponse.semanticSearchActive` this module cannot know, because it is configuration and a
 * runtime switch that live elsewhere.
 *
 * A port, defined here, where it is asked, and implemented by `gratitude`, which depends on this
 * module and owns the predicate: its `SemanticSearchReadiness` is the one place search and
 * "Remember when" decide whether to use their meaning half, and this answers from the same call.
 * `false` while no embedding provider is configured, while the provider's model has no
 * max-distance, or while `AI_EMBEDDINGS` is switched off (ADR-0054, ADR-0067 decision 7).
 *
 * The bond's setting is the other half, and is not asked here: a bond's entries are searched by
 * meaning exactly when its `semanticSearchEnabled` is true **and** this is.
 */
fun interface SemanticSearchAvailability {
    fun isAvailable(): Boolean
}
