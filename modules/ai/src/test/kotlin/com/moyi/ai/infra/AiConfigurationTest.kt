package com.moyi.ai.infra

import com.moyi.ai.api.EmbeddingSpace
import com.moyi.ai.api.EmbeddingUnavailableException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

/**
 * The provider is chosen by configuration, the real one is the default, and a real one without
 * its key refuses to start, naming the variable that is missing (OPENAI's case is in OpenAiWiringTest). Building a model contacts
 * nothing, so these run without a network; the HTTP each model sends is Task 3's to test.
 */
internal class AiConfigurationTest {
    private fun port(properties: AiProperties) = AiConfiguration().embeddingPort(properties, SimpleMeterRegistry(), RestClient.builder())

    @Test
    fun `none has no space, and every request is refused as no provider`() {
        val port = port(AiProperties(provider = AiProperties.Provider.NONE))
        port.space.shouldBeNull()
        shouldThrow<EmbeddingUnavailableException> { port.embed(listOf("x")) }.message shouldBe "no provider"
    }

    @Test
    fun `openai with a key makes vectors in OpenAI's space`() {
        port(AiProperties(openai = AiProperties.OpenAi(apiKey = "sk-test"))).space shouldBe EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL
    }

    @Test
    fun `voyage with a key makes vectors in Voyage's space`() {
        port(
            AiProperties(provider = AiProperties.Provider.VOYAGE, voyage = AiProperties.Voyage(apiKey = "pa-test")),
        ).space shouldBe EmbeddingSpace.VOYAGE_3_5
    }

    @Test
    fun `a blank key is no key`() {
        shouldThrow<IllegalStateException> {
            port(AiProperties(provider = AiProperties.Provider.VOYAGE, voyage = AiProperties.Voyage(apiKey = " ")))
        }.message shouldContain "MOYI_AI_EMBEDDING_VOYAGE_API_KEY"
    }
}
