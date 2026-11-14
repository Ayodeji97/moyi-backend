package com.moyi.ai.infra

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingSpace
import com.moyi.ai.api.EmbeddingUnavailableException
import com.moyi.common.core.FeatureFlag
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-0067 decision 7: while `AI_EMBEDDINGS` is off nothing reaches the provider, the refusal is the
 * exception every caller already handles, and the space stays what is stored.
 */
internal class SwitchedEmbeddingPortTest {
    private val on = AtomicBoolean(true)
    private val asked = mutableListOf<List<String>>()
    private val provider =
        object : EmbeddingPort {
            override val space = EmbeddingSpace.VOYAGE_3_5

            override fun embed(texts: List<String>): List<FloatArray> {
                asked += texts
                return texts.map { FloatArray(1) { 0.5f } }
            }
        }
    private val port = SwitchedEmbeddingPort(provider) { flag -> flag == FeatureFlag.AI_EMBEDDINGS && on.get() }

    @Test
    fun `on, the provider answers`() {
        port.embed(listOf("tea with my sister")).single()[0] shouldBe 0.5f
        asked.size shouldBe 1
    }

    @Test
    fun `off, the call refuses before the provider is asked, and the reason carries no text`() {
        on.set(false)

        val refused = shouldThrow<EmbeddingUnavailableException> { port.embed(listOf("tea with my sister")) }

        asked.size shouldBe 0
        refused.message shouldBe "switched off"
        refused.toString() shouldNotContain "sister"
    }

    @Test
    fun `off, the space is still the provider's, and on again the provider is asked`() {
        on.set(false)
        port.space shouldBe EmbeddingSpace.VOYAGE_3_5

        on.set(true)
        port.embed(listOf("a"))
        asked.size shouldBe 1
    }
}
