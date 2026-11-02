package com.moyi.ai.infra

import com.moyi.ai.api.EmbeddingSpace
import com.moyi.ai.api.EmbeddingUnavailableException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse

internal class SpringAiEmbeddingAdapterTest {
    private companion object {
        val OPENAI_SIZE = EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL.dimensions
    }

    private val meters = SimpleMeterRegistry()

    private fun model(answer: (List<String>) -> List<FloatArray>) =
        object : EmbeddingModel {
            override fun call(request: EmbeddingRequest) =
                EmbeddingResponse(answer(request.instructions).mapIndexed { i, v -> Embedding(v, i) })

            override fun embed(document: Document): FloatArray = error("unused")
        }

    @Test
    fun `returns one vector per text in order and counts the request`() {
        val adapter =
            SpringAiEmbeddingAdapter(
                // Full-size vectors, the text's length first: the adapter refuses any other size.
                model { texts -> texts.map { text -> FloatArray(OPENAI_SIZE).also { it[0] = text.length.toFloat() } } },
                EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL,
                meters,
                "openai",
            )
        adapter.embed(listOf("ab", "abcd")).map { it[0] } shouldBe listOf(2f, 4f)
        meters.counter("gratitude.ai.embedding.requests", "provider", "openai", "outcome", "ok").count() shouldBe 1.0
    }

    @Test
    fun `a provider failure becomes EmbeddingUnavailableException without the text`() {
        val adapter =
            SpringAiEmbeddingAdapter(
                model { throw IllegalStateException("boom: secret words") },
                EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL,
                meters,
                "openai",
            )
        val thrown = shouldThrow<EmbeddingUnavailableException> { adapter.embed(listOf("secret words")) }
        thrown.message shouldBe "provider error"
        meters.counter("gratitude.ai.embedding.requests", "provider", "openai", "outcome", "failed").count() shouldBe 1.0
    }

    /** Review of Task 7: an interrupt wrapped by a client must still reach the caller as an interrupt. */
    @Test
    fun `an interrupted call keeps the thread's interrupt, however the client wrapped it`() {
        listOf<Throwable>(
            InterruptedException(),
            IllegalStateException("retry interrupted", InterruptedException()),
        ).forEach { failure ->
            val adapter = SpringAiEmbeddingAdapter(model { throw failure }, EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL, meters, "openai")
            try {
                shouldThrow<EmbeddingUnavailableException> { adapter.embed(listOf("words")) }
                Thread.currentThread().isInterrupted shouldBe true
            } finally {
                Thread.interrupted()
            }
        }
    }

    /** Review of Task 7, round 2: a read timeout is an `InterruptedIOException` too, and nobody asked the thread to stop. */
    @Test
    fun `a provider timeout fails the call and leaves the thread uninterrupted`() {
        // A read timeout, and OkHttp's whole-call timeout: both `InterruptedIOException`s, neither an interrupt.
        listOf<Throwable>(
            IllegalStateException("I/O error", java.net.SocketTimeoutException("Read timed out")),
            IllegalStateException("invalid data", java.io.InterruptedIOException("timeout")),
        ).forEach { failure ->
            val adapter = SpringAiEmbeddingAdapter(model { throw failure }, EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL, meters, "openai")
            try {
                shouldThrow<EmbeddingUnavailableException> { adapter.embed(listOf("words")) }
                Thread.currentThread().isInterrupted shouldBe false
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `a vector of the wrong size is refused`() {
        val adapter =
            SpringAiEmbeddingAdapter(
                model { t -> t.map { floatArrayOf(1f) } },
                EmbeddingSpace.VOYAGE_3_5,
                meters,
                "voyage",
            )
        shouldThrow<EmbeddingUnavailableException> { adapter.embed(listOf("x")) }.message shouldBe "wrong dimensions"
    }
}
