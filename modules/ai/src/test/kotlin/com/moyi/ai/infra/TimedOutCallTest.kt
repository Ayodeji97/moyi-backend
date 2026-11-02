package com.moyi.ai.infra

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingSpace
import com.moyi.ai.api.EmbeddingUnavailableException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * A provider timeout is a failure, not an interrupt (review of Task 7, round 2). A read timeout
 * surfaces as `SocketTimeoutException`, an `InterruptedIOException`; were it taken for an
 * interrupt, the flag would follow the sweep's thread into its next bond's call. So: a call
 * times out, and the next call on the same thread, as the sweep makes it, succeeds.
 *
 * Each provider is wired as production wires it ([AiConfiguration]), against a stub that stalls
 * the first call and answers the second.
 */
internal class TimedOutCallTest {
    @AfterEach
    fun clear() {
        Thread.interrupted()
    }

    @Test
    fun `after an OpenAI timeout the thread is not interrupted and the next call succeeds`() {
        StubHttpServer("/v1/embeddings").use { stub ->
            val port =
                AiConfiguration().embeddingPort(
                    AiProperties(
                        provider = AiProperties.Provider.OPENAI,
                        openai = AiProperties.OpenAi(apiKey = "sk-test", baseUrl = "${stub.baseUrl}/v1"),
                        timeout = CLIENT_TIMEOUT,
                    ),
                    SimpleMeterRegistry(),
                    RestClient.builder(),
                )
            val vector = List(EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL.dimensions) { "0.0" }.joinToString(",")

            timesOutThenSucceeds(
                stub,
                port,
                """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[$vector]}],""" +
                    """"model":"text-embedding-3-small","usage":{"prompt_tokens":1,"total_tokens":1}}""",
            )
        }
    }

    /**
     * OkHttp's whole-call timeout, which Spring AI sets beside the read timeout: a body that
     * trickles in never trips the read timeout, and the call ends with a plain
     * `InterruptedIOException("timeout")`, not a `SocketTimeoutException`. Still not an interrupt.
     */
    @Test
    fun `after an OpenAI call timeout on a slow body the thread is not interrupted and the next call succeeds`() {
        StubHttpServer("/v1/embeddings").use { stub ->
            val port =
                AiConfiguration().embeddingPort(
                    AiProperties(
                        provider = AiProperties.Provider.OPENAI,
                        openai = AiProperties.OpenAi(apiKey = "sk-test", baseUrl = "${stub.baseUrl}/v1"),
                        timeout = CLIENT_TIMEOUT,
                    ),
                    SimpleMeterRegistry(),
                    RestClient.builder(),
                )
            val vector = List(EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL.dimensions) { "0.0" }.joinToString(",")

            timesOutThenSucceeds(
                stub,
                port,
                """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[$vector]}],""" +
                    """"model":"text-embedding-3-small","usage":{"prompt_tokens":1,"total_tokens":1}}""",
                slowBody = true,
            )
        }
    }

    @Test
    fun `after a Voyage timeout the thread is not interrupted and the next call succeeds`() {
        StubHttpServer("/v1/embeddings").use { stub ->
            val port =
                AiConfiguration().embeddingPort(
                    AiProperties(
                        provider = AiProperties.Provider.VOYAGE,
                        voyage = AiProperties.Voyage(apiKey = "pa-test", baseUrl = stub.baseUrl),
                        timeout = CLIENT_TIMEOUT,
                    ),
                    SimpleMeterRegistry(),
                    RestClient.builder(),
                )
            val vector = List(EmbeddingSpace.VOYAGE_3_5.dimensions) { "0.0" }.joinToString(",")

            timesOutThenSucceeds(stub, port, """{"data":[{"embedding":[$vector],"index":0}],"usage":{"total_tokens":1}}""")
        }
    }

    private fun timesOutThenSucceeds(
        stub: StubHttpServer,
        port: EmbeddingPort,
        answer: String,
        slowBody: Boolean = false,
    ) {
        if (slowBody) stub.trickle = TRICKLE else stub.stall = true
        val failure = shouldThrow<EmbeddingUnavailableException> { port.embed(listOf("first bond")) }
        println("timeout cause chain: " + generateSequence<Throwable>(failure) { it.cause }.joinToString(" <- ") { it.javaClass.name })

        Thread.currentThread().isInterrupted shouldBe false
        stub.stall = false
        stub.trickle = null
        stub.responses += 200 to answer
        port.embed(listOf("second bond")).size shouldBe 1
    }

    private companion object {
        val CLIENT_TIMEOUT: Duration = Duration.ofMillis(500)

        /** Well inside [CLIENT_TIMEOUT]: no single read waits long enough to time out. */
        val TRICKLE: Duration = Duration.ofMillis(50)
    }
}
