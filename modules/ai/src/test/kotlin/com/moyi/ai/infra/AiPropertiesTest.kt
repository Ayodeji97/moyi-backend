package com.moyi.ai.infra

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

internal class AiPropertiesTest {
    @Test
    fun `redacts keys in toString`() {
        val p =
            AiProperties(
                provider = AiProperties.Provider.OPENAI,
                openai = AiProperties.OpenAi(apiKey = "sk-secret"),
                voyage = AiProperties.Voyage(apiKey = "pa-secret"),
            )
        p.toString() shouldNotContain "sk-secret"
        p.toString() shouldNotContain "pa-secret"
    }

    @Test
    fun `the real provider is the default`() {
        AiProperties().provider shouldBe AiProperties.Provider.OPENAI
    }
}
