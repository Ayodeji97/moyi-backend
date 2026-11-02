package com.moyi.gratitude.service

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingUnavailableException
import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.SemanticMatch
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * `q` as a vector, for the search's meaning half (ADR-0054, spec §3.5), or `null` whenever that
 * half must be dropped and the search is keywords only, still `200`.
 *
 * **Dropped, counted `disabled`**, before anything is sent: the bond's setting is off, no
 * provider is configured, or the provider's model has no threshold
 * ([SemanticSearchProperties.maxDistance]). With the setting off, `q` never leaves the server.
 *
 * **Dropped, counted `degraded`**, when the provider fails, answers with a vector of the wrong
 * size, or has not answered within [SemanticSearchProperties.queryTimeout] (5 s). The call runs
 * on [executor], one for the bean's life, and is **cancelled** at the timeout: its thread is
 * interrupted, so a hung provider call does not run on behind a page already sent.
 *
 * Called outside any transaction (the controller holds none), so a slow provider holds no
 * connection. **Logs nothing of the query**; an unexpected failure is logged by its class alone,
 * since a provider's message may quote its input.
 */
@Component
internal class QueryEmbedder(
    private val port: EmbeddingPort,
    private val properties: SemanticSearchProperties,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Virtual threads: each waits on the network and nothing else. Shut down with the bean. */
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    private val used = counter(meters, "used")
    private val disabled = counter(meters, "disabled")
    private val degraded = counter(meters, "degraded")

    /** `null` whenever the semantic half must be dropped: setting off, no provider, no threshold, timeout, error. Never throws. */
    fun matchFor(
        membership: BondMembership,
        query: String,
    ): SemanticMatch? {
        val space = port.space
        val maxDistance = space?.let { properties.maxDistance[it] }
        if (!membership.semanticSearchEnabled || space == null || maxDistance == null) {
            disabled.increment()
            return null
        }
        val match = embed(query)?.takeIf { it.size == space.dimensions }?.let { SemanticMatch(space, it, maxDistance) }
        (if (match == null) degraded else used).increment()
        return match
    }

    /** The one call, bounded by the timeout; `null` on any failure. */
    private fun embed(query: String): FloatArray? {
        // Inside the `try`: a submit the executor refuses (shut down) is a failure like any other.
        var future: Future<FloatArray>? = null
        return try {
            future = executor.submit<FloatArray> { port.embed(listOf(query)).single() }
            future.get(properties.queryTimeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            future?.cancel(true)
            null
        } catch (_: InterruptedException) {
            future?.cancel(true)
            Thread.currentThread().interrupt()
            null
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            if (cause !is EmbeddingUnavailableException) log.warn("Embedding a search query failed: {}", cause?.javaClass?.name)
            null
        } catch (failure: RejectedExecutionException) {
            // The executor is shut down: the search goes on without its meaning half.
            log.warn("Embedding a search query failed: {}", failure.javaClass.name)
            null
        }
    }

    @PreDestroy
    fun close() {
        executor.shutdownNow()
    }

    private fun counter(
        meters: MeterRegistry,
        outcome: String,
    ): Counter = meters.counter(METRIC, "outcome", outcome)

    private companion object {
        const val METRIC = "gratitude.search.semantic"
    }
}
