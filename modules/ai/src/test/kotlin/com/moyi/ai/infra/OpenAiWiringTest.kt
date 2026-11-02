package com.moyi.ai.infra

import com.moyi.ai.api.EmbeddingSpace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.URI

/**
 * Both real providers, wired as production wires them (AiConfiguration), against a stub on a
 * real socket: the base URL, the key in the header, and the response mapped into the port.
 */
internal class OpenAiWiringTest {
    @Test
    fun `the OpenAI adapter talks to the configured base URL and maps the response`() {
        StubHttpServer("/v1/embeddings").use { stub ->
            val vector = List(EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL.dimensions) { "0.0" }.joinToString(",")
            stub.responses += 200 to
                """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[$vector]}],""" +
                """"model":"text-embedding-3-small","usage":{"prompt_tokens":1,"total_tokens":1}}"""
            val port =
                AiConfiguration().embeddingPort(
                    AiProperties(
                        provider = AiProperties.Provider.OPENAI,
                        openai = AiProperties.OpenAi(apiKey = "sk-test", baseUrl = "${stub.baseUrl}/v1"),
                    ),
                    SimpleMeterRegistry(),
                    RestClient.builder(),
                )
            port.embed(listOf("hello")).single().size shouldBe EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL.dimensions
            stub.authorizations.single() shouldBe "Bearer sk-test"
        }
    }

    @Test
    fun `the default OpenAI base URL carries the path the SDK appends embeddings to`() {
        // The SDK uses a configured base URL verbatim and adds only `/embeddings`, so the default
        // must end in `/v1`. The stub stands in for the host; the path is the default's own.
        val defaultPath = URI(AiProperties.OpenAi().baseUrl).path
        StubHttpServer("/v1/embeddings").use { stub ->
            val vector = List(EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL.dimensions) { "0.0" }.joinToString(",")
            stub.responses += 200 to """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[$vector]}],""" +
                """"model":"text-embedding-3-small","usage":{"prompt_tokens":1,"total_tokens":1}}"""
            val port =
                AiConfiguration().embeddingPort(
                    AiProperties(openai = AiProperties.OpenAi(apiKey = "sk-test", baseUrl = stub.baseUrl + defaultPath)),
                    SimpleMeterRegistry(),
                    RestClient.builder(),
                )
            port.embed(listOf("hello")).single().size shouldBe EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL.dimensions
            stub.requests.size shouldBe 1
        }
    }

    @Test
    fun `the Voyage adapter talks to the configured base URL with its key`() {
        StubHttpServer("/v1/embeddings").use { stub ->
            val vector = List(EmbeddingSpace.VOYAGE_3_5.dimensions) { "0.0" }.joinToString(",")
            stub.responses += 200 to """{"data":[{"embedding":[$vector],"index":0}],"usage":{"total_tokens":1}}"""
            val port =
                AiConfiguration().embeddingPort(
                    AiProperties(
                        provider = AiProperties.Provider.VOYAGE,
                        voyage = AiProperties.Voyage(apiKey = "pa-test", baseUrl = stub.baseUrl),
                    ),
                    SimpleMeterRegistry(),
                    RestClient.builder(),
                )
            port.embed(listOf("hello")).single().size shouldBe EmbeddingSpace.VOYAGE_3_5.dimensions
            stub.authorizations.single() shouldBe "Bearer pa-test"
        }
    }

    @Test
    fun `OPENAI without a key refuses to start`() {
        shouldThrow<IllegalStateException> {
            AiConfiguration().embeddingPort(AiProperties(), SimpleMeterRegistry(), RestClient.builder())
        }.message shouldContain "MOYI_AI_EMBEDDING_OPENAI_API_KEY"
    }
}
