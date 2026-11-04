package com.moyi.ai.infra

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.SocketTimeoutException
import java.time.Duration

internal class VoyageEmbeddingModelTest {
    private val stub = StubHttpServer("/v1/embeddings")

    @AfterEach
    fun close() = stub.close()

    private fun model(timeout: Duration = Duration.ofSeconds(2)) =
        VoyageEmbeddingModel(
            RestClient
                .builder()
                .baseUrl(stub.baseUrl)
                .requestFactory(
                    SimpleClientHttpRequestFactory().apply {
                        setConnectTimeout(timeout)
                        setReadTimeout(timeout)
                    },
                ).build(),
            "voyage-3.5",
        )

    @Test
    fun `sends the texts as documents and returns vectors by index`() {
        stub.responses +=
            200 to """{"data":[{"embedding":[0.2,0.1],"index":1},{"embedding":[0.9,0.8],"index":0}],"usage":{"total_tokens":7}}"""
        val out = model().call(EmbeddingRequest(listOf("a", "b"), null)).results.sortedBy { it.index }
        out.map { it.output.toList() } shouldBe listOf(listOf(0.9f, 0.8f), listOf(0.2f, 0.1f))
        stub.requests.single() shouldContain "\"input_type\":\"document\""
        stub.requests.single() shouldContain "\"model\":\"voyage-3.5\""
        stub.requests.single() shouldContain "\"input\":[\"a\",\"b\"]"
    }

    @Test
    fun `a 429 then 200 is retried once`() {
        stub.responses += 429 to """{"detail":"rate"}"""
        stub.responses += 200 to """{"data":[{"embedding":[1.0],"index":0}],"usage":{"total_tokens":1}}"""
        model().call(EmbeddingRequest(listOf("a"), null)).results.size shouldBe 1
        stub.requests.size shouldBe 2
    }

    @Test
    fun `a 500 twice is given up after one retry`() {
        stub.responses += 500 to "oops"
        stub.responses += 500 to "oops"
        shouldThrow<IllegalStateException> { model().call(EmbeddingRequest(listOf("secret"), null)) }
            .message shouldNotContain "secret"
        stub.requests.size shouldBe 2
    }

    @Test
    fun `a 400 is not retried, and names the status without echoing the input`() {
        stub.responses += 400 to """{"detail":"bad input: secret"}"""
        val thrown = shouldThrow<IllegalStateException> { model().call(EmbeddingRequest(listOf("secret"), null)) }
        thrown.message shouldBe "Voyage refused the request: 400"
        stub.requests.size shouldBe 1
    }

    @Test
    fun `a malformed body throws without echoing the input`() {
        stub.responses += 200 to """{"nope":true}"""
        shouldThrow<IllegalStateException> { model().call(EmbeddingRequest(listOf("secret"), null)) }
            .message shouldNotContain "secret"
    }

    @Test
    fun `a slow provider times out`() {
        // A valid answer, only late: without the read timeout the call would succeed.
        stub.delay = Duration.ofSeconds(3)
        stub.responses += 200 to """{"data":[{"embedding":[1.0],"index":0}],"usage":{"total_tokens":1}}"""
        val started = System.nanoTime()
        val thrown =
            shouldThrow<ResourceAccessException> {
                model(timeout = Duration.ofMillis(300)).call(EmbeddingRequest(listOf("a"), null))
            }
        thrown.cause.shouldBeInstanceOf<SocketTimeoutException>()
        Duration.ofNanos(System.nanoTime() - started) shouldBeLessThan Duration.ofSeconds(2)
    }
}
