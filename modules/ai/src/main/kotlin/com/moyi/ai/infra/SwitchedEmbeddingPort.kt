package com.moyi.ai.infra

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingSpace
import com.moyi.ai.api.EmbeddingUnavailableException
import com.moyi.common.core.FeatureFlag
import com.moyi.common.core.FeatureFlags

/**
 * The application's [EmbeddingPort]: whichever provider is configured, behind the `AI_EMBEDDINGS`
 * flag (FR-115, ADR-0067 decision 7). While the flag is off, every call refuses before anything
 * leaves the process, with the exception every caller already handles: a search is keywords only
 * (ADR-0054's degraded path) and the embedding job, which skips its run anyway, embeds nothing.
 *
 * Wraps every provider, `NONE` included, so that there is one place the flag is read and no
 * provider can be reached around it. [space] is the provider's even while off: what is stored was
 * made in that space and stays searchable once the flag is on again; nothing is erased.
 */
internal class SwitchedEmbeddingPort(
    private val provider: EmbeddingPort,
    private val flags: FeatureFlags,
) : EmbeddingPort {
    override val space: EmbeddingSpace? get() = provider.space

    override fun embed(texts: List<String>): List<FloatArray> {
        if (!flags.isOn(FeatureFlag.AI_EMBEDDINGS)) throw EmbeddingUnavailableException(SWITCHED_OFF)
        return provider.embed(texts)
    }

    internal companion object {
        /** The refusal's reason: what a log line says, and nothing of the texts. */
        const val SWITCHED_OFF = "switched off"
    }
}
