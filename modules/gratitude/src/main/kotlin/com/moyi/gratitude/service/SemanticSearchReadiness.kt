package com.moyi.gratitude.service

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingSpace
import com.moyi.bond.api.SemanticSearchAvailability
import com.moyi.common.core.FeatureFlag
import com.moyi.common.core.FeatureFlags
import org.springframework.stereotype.Component

/**
 * **The one place** search and "Remember when" decide whether their meaning half can run at all,
 * before the bond's setting is asked (ADR-0054, ADR-0070): `AI_EMBEDDINGS` on (ADR-0067 decision
 * 7), a provider configured ([EmbeddingPort.space]), and a max-distance for its model
 * ([SemanticSearchProperties.maxDistance]). [QueryEmbedder] asks [ready] for the space and the
 * threshold it embeds `q` with; `bond` asks [isAvailable], through its own port, for
 * `BondResponse.semanticSearchActive`. One predicate, so the field cannot say "by meaning" while
 * search runs on keywords, nor the other way round.
 *
 * Reads the flag on every call, as every flag read does: nothing here is cached.
 */
@Component
internal class SemanticSearchReadiness(
    private val port: EmbeddingPort,
    private val properties: SemanticSearchProperties,
    private val flags: FeatureFlags,
) : SemanticSearchAvailability {
    /** What a search embeds `q` in and matches within, or `null` when nothing may be embedded now. */
    fun ready(): Ready? {
        // The flag first, as before this class: with it off, nothing else is asked.
        if (!flags.isOn(FeatureFlag.AI_EMBEDDINGS)) return null
        return port.space?.let { space -> properties.maxDistance[space]?.let { Ready(space, it) } }
    }

    override fun isAvailable(): Boolean = ready() != null

    /** The provider's [space] and its model's [maxDistance]: everything a search needs to ask by meaning. */
    data class Ready(
        val space: EmbeddingSpace,
        val maxDistance: Double,
    )
}
