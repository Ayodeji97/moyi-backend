package com.moyi.tools.embeddingeval

import com.moyi.ai.api.EmbeddingUnavailableException
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/** A provider's failure prints its name, the exception classes and a status: never a message, which may quote the input. */
internal class ProviderFailureTest {
    @Test
    fun `the port's classes and status, and no message`() {
        val described =
            ProviderFailure.describe("openai", EmbeddingUnavailableException("provider error", failure = "ServiceException", status = 401))

        described shouldBe "openai failed: EmbeddingUnavailableException < ServiceException, HTTP 401"
        described shouldNotContain "secret"
    }

    @Test
    fun `no status, the classes alone`() {
        ProviderFailure.describe("voyage", IllegalStateException("the secret entry text")) shouldBe "voyage failed: IllegalStateException"
    }

    @Test
    fun `Voyage's refusal through modules-ai's adapter gives its status, and none of the body`() {
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/embeddings") { exchange ->
                    exchange.requestBody.readAllBytes()
                    val body = "{\"detail\": \"the secret entry text\"}".toByteArray()
                    exchange.sendResponseHeaders(401, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
        try {
            val overrides = mapOf("moyi.ai.embedding.voyage.base-url" to "http://127.0.0.1:${server.address.port}")
            Providers.from(mapOf(Providers.VOYAGE_KEY to "pa-test"), overrides).use { providers ->
                val failure = shouldThrow<EmbeddingUnavailableException> { providers.single().port.embed(listOf("the secret entry text")) }

                val described = ProviderFailure.describe("voyage", failure)
                described shouldBe "voyage failed: EmbeddingUnavailableException < IllegalStateException, HTTP 401"
                described shouldNotContain "secret"
            }
        } finally {
            server.stop(0)
        }
    }
}
