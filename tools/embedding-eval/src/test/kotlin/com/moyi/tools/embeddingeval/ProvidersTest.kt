package com.moyi.tools.embeddingeval

import com.moyi.ai.api.EmbeddingSpace
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress

/**
 * A provider is run exactly when its key is in the environment, and it is `modules:ai`'s own
 * adapter that is run: here, Voyage's, against a JDK `HttpServer` answering as Voyage does.
 */
internal class ProvidersTest {
    @Test
    fun `no key, no provider`() {
        Providers.from(emptyMap()).shouldBeEmpty()
        Providers.from(mapOf(Providers.OPENAI_KEY to " ")).shouldBeEmpty()
    }

    @Test
    fun `each key in the environment runs its provider, in its own space`() {
        Providers.from(mapOf(Providers.OPENAI_KEY to "sk-test", Providers.VOYAGE_KEY to "pa-test")).use { providers ->
            providers.map { it.port.space } shouldBe listOf(EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL, EmbeddingSpace.VOYAGE_3_5)
            providers.forEach { it.toString() shouldNotContain "test" }
        }
    }

    @Test
    fun `the vectors come through modules-ai's adapter, which sent the key`() {
        val authorizations = mutableListOf<String?>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/embeddings") { exchange ->
                    authorizations += exchange.requestHeaders.getFirst("Authorization")
                    val inputs = JsonMapper().readTree(exchange.requestBody.readAllBytes())["input"].size()
                    val vector = List(EmbeddingSpace.VOYAGE_3_5.dimensions) { 0.5 }
                    val body =
                        JsonMapper().writeValueAsBytes(
                            mapOf("data" to List(inputs) { mapOf("embedding" to vector, "index" to it) }),
                        )
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
        try {
            val environment = mapOf(Providers.VOYAGE_KEY to "pa-test")
            val overrides = mapOf("moyi.ai.embedding.voyage.base-url" to "http://127.0.0.1:${server.address.port}")
            Providers.from(environment, overrides).use { providers ->
                val vectors = providers.single().port.embed(listOf("one", "two"))

                vectors.map { it.size } shouldBe listOf(1024, 1024)
                authorizations shouldBe listOf("Bearer pa-test")
            }
        } finally {
            server.stop(0)
        }
    }
}
