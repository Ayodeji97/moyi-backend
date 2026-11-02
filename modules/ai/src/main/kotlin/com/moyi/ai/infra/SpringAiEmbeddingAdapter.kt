package com.moyi.ai.infra

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingSpace
import com.moyi.ai.api.EmbeddingUnavailableException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import java.time.Duration

/**
 * The port over any Spring AI [EmbeddingModel]: OpenAI's, or our Voyage one. It checks what the
 * provider answered against the [space] it claims, turns every failure into an
 * [EmbeddingUnavailableException] with a fixed reason, and counts each request by outcome.
 */
internal class SpringAiEmbeddingAdapter(
    private val model: EmbeddingModel,
    override val space: EmbeddingSpace,
    private val meters: MeterRegistry,
    private val provider: String,
) : EmbeddingPort {
    override fun embed(texts: List<String>): List<FloatArray> {
        val started = System.nanoTime()
        try {
            val vectors = call(texts)
            if (vectors.size != texts.size || vectors.any { it.size != space.dimensions }) {
                throw EmbeddingUnavailableException("wrong dimensions")
            }
            record("ok", started)
            return vectors
        } catch (e: EmbeddingUnavailableException) {
            record("failed", started)
            throw e
        }
    }

    /** Any provider exception becomes a fixed reason: its message may quote the input. */
    @Suppress("TooGenericExceptionCaught")
    private fun call(texts: List<String>): List<FloatArray> =
        try {
            model
                .call(EmbeddingRequest(texts, null))
                .results
                .sortedBy { it.index }
                .map { it.output }
        } catch (e: Exception) {
            throw EmbeddingUnavailableException("provider error", e)
        }

    private fun record(
        outcome: String,
        started: Long,
    ) {
        meters.counter(REQUESTS, "provider", provider, "outcome", outcome).increment()
        Timer
            .builder(LATENCY)
            .tag("provider", provider)
            .register(meters)
            .record(Duration.ofNanos(System.nanoTime() - started))
    }

    companion object {
        const val REQUESTS = "gratitude.ai.embedding.requests"
        const val LATENCY = "gratitude.ai.embedding.latency"
    }
}

/** `moyi.ai.embedding.provider: none`: no space, and every request refused, so callers fall back to keywords. */
internal object NoEmbeddings : EmbeddingPort {
    override val space: EmbeddingSpace? = null

    override fun embed(texts: List<String>): List<FloatArray> = throw EmbeddingUnavailableException("no provider")
}
