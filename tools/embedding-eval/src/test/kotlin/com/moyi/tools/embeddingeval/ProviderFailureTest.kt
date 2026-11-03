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
        // The marker rides in the message, where a provider's body would: describe must not print it.
        val described =
            ProviderFailure.describe("openai", EmbeddingUnavailableException(MARKER, failure = "ServiceException", status = 401))

        described shouldBe "openai failed: EmbeddingUnavailableException < ServiceException, HTTP 401"
        described shouldNotContain MARKER
    }

    @Test
    fun `no status, the classes alone`() {
        ProviderFailure.describe("voyage", IllegalStateException(MARKER)) shouldBe "voyage failed: IllegalStateException"
    }

    @Test
    fun `Voyage's refusal through modules-ai's adapter gives its status, and none of the body`() {
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/embeddings") { exchange ->
                    exchange.requestBody.readAllBytes()
                    val body = "{\"detail\": \"$MARKER\"}".toByteArray()
                    exchange.sendResponseHeaders(401, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
        try {
            val overrides = mapOf("moyi.ai.embedding.voyage.base-url" to "http://127.0.0.1:${server.address.port}")
            Providers.from(mapOf(Providers.VOYAGE_KEY to "pa-test"), overrides).use { providers ->
                val failure = shouldThrow<EmbeddingUnavailableException> { providers.single().port.embed(listOf(MARKER)) }

                val described = ProviderFailure.describe("voyage", failure)
                described shouldBe "voyage failed: EmbeddingUnavailableException < IllegalStateException, HTTP 401"
                described shouldNotContain MARKER
                // The marker was both the input and the provider's body: none of it rides on the exception.
                failure.message!! shouldNotContain MARKER
                failure.failure!! shouldNotContain MARKER
                failure.cause shouldBe null
            }
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        /** Stands for entry text: in a test's input or a provider's body, and never in what is printed. */
        const val MARKER = "MARKER-the-secret-entry-text-7f3a"
    }
}
