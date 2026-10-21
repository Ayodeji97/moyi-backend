package com.moyi.gratitude.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The exact tag, then its language, then `en` (ADR-0038, controller ruling 3; decision 5). */
internal class PromptLocaleTest {
    private val texts = mapOf("en" to "EN", "pt-pt" to "PT-PT", "pt" to "PT", "fr" to "FR")

    @Test
    fun `the exact tag, then its language, then en, whatever case it was stored in`() {
        PromptLocale.candidates("pt-PT") shouldBe listOf("pt-pt", "pt", "en")
        PromptLocale.candidates("PT-pt") shouldBe listOf("pt-pt", "pt", "en")
        PromptLocale.candidates(" fr ") shouldBe listOf("fr", "en")
        PromptLocale.candidates("en-GB") shouldBe listOf("en-gb", "en")
        PromptLocale.candidates("en") shouldBe listOf("en")
    }

    @Test
    fun `no tag at all is en`() {
        PromptLocale.candidates(null) shouldBe listOf("en")
        PromptLocale.candidates("") shouldBe listOf("en")
        PromptLocale.candidates("   ") shouldBe listOf("en")
    }

    @Test
    fun `the first candidate with a text wins, and en is the floor`() {
        PromptLocale.textIn(PromptLocale.candidates("pt-PT"), texts) shouldBe "PT-PT"
        PromptLocale.textIn(PromptLocale.candidates("pt-BR"), texts) shouldBe "PT"
        PromptLocale.textIn(PromptLocale.candidates("de"), texts) shouldBe "EN"
        PromptLocale.textIn(PromptLocale.candidates("fr-CA"), texts) shouldBe "FR"
        PromptLocale.textIn(PromptLocale.candidates("pt-PT"), mapOf("en" to "EN")) shouldBe "EN"
        PromptLocale.textIn(listOf("de"), mapOf("en" to "EN")).shouldBeNull()
    }
}
