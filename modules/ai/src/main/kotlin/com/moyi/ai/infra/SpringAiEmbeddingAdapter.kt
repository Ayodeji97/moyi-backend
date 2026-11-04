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

    /**
     * Any provider exception becomes a fixed reason: its message may quote the input.
     *
     * **A real interrupt in its causes is put back on the thread**, which catching it cleared:
     * the caller cancelled the call (the search's timeout) or is shutting down (the sweep), and
     * must still see that it was asked to stop. Only an [InterruptedException] is one. An
     * `InterruptedIOException` is how both clients report a **timeout** (a read timeout is a
     * `SocketTimeoutException`, OkHttp's whole-call timeout a plain `InterruptedIOException`;
     * `TimedOutCallTest` sees each), and taking one for an interrupt would carry the flag into the
     * sweep's next bond. An interrupt Okio meets during I/O it reports the same way, but Okio
     * keeps the thread's status set itself.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun call(texts: List<String>): List<FloatArray> =
        try {
            model
                .call(EmbeddingRequest(texts, null))
                .results
                .sortedBy { it.index }
                .map { it.output }
        } catch (e: Exception) {
            val chain = generateSequence<Throwable>(e) { it.cause }.toList()
            // Asked of the chain before it is dropped: the exception thrown carries none of it.
            if (chain.any { it is InterruptedException }) Thread.currentThread().interrupt()
            throw EmbeddingUnavailableException(
                "provider error",
                failure = chain.joinToString(" < ") { it.javaClass.simpleName },
                status = chain.firstNotNullOfOrNull(::statusOf),
            )
        }

    /**
     * An HTTP status read without reading a body: a `statusCode()` method (the OpenAI SDK's
     * service exceptions), or [VoyageEmbeddingModel]'s own refusal, its message matched whole.
     */
    private fun statusOf(failure: Throwable): Int? =
        failure.javaClass.methods
            .firstOrNull { it.name == "statusCode" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(failure) as? Int }.getOrNull() }
            ?: failure.message
                ?.let { VOYAGE_REFUSAL.matchEntire(it) }
                ?.groupValues
                ?.get(1)
                ?.toInt()

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

        private val VOYAGE_REFUSAL = Regex("""Voyage refused the request: (\d{3})""")
    }
}

/** `moyi.ai.embedding.provider: none`: no space, and every request refused, so callers fall back to keywords. */
internal object NoEmbeddings : EmbeddingPort {
    override val space: EmbeddingSpace? = null

    override fun embed(texts: List<String>): List<FloatArray> = throw EmbeddingUnavailableException("no provider")
}
