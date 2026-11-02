package com.moyi.gratitude.service

import com.moyi.ai.api.EmbeddingSpace
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * How the search box asks by meaning (ADR-0054, spec §3.5).
 *
 * [queryTimeout] bounds the one provider call a search makes to embed `q` (5 s, spec §3.5);
 * past it the search is keywords only. [maxDistance] is the cosine distance within which an
 * entry's vector matches `q`'s, **per model**, because each model's distances are its own: set
 * from the eval (Task 9, precision ≥ 0.9). A space with no value is not searched by meaning at
 * all, so a model nobody has measured never shows a stray day.
 */
@ConfigurationProperties("moyi.gratitude.semantic-search")
internal data class SemanticSearchProperties(
    val queryTimeout: Duration = Duration.ofSeconds(QUERY_TIMEOUT_SECONDS),
    val maxDistance: Map<EmbeddingSpace, Double> = mapOf(),
) {
    private companion object {
        /** Spec §3.5. */
        const val QUERY_TIMEOUT_SECONDS = 5L
    }
}

@Configuration
@EnableConfigurationProperties(SemanticSearchProperties::class)
internal class SemanticSearchConfiguration
