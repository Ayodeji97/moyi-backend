package com.moyi.gratitude.service

import com.moyi.ai.api.EmbeddingSpace
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/**
 * The search's meaning half, through Boot's binding: what a deployment that sets nothing gets.
 * The test configuration shortens the timeout, so its production value is held here.
 */
internal class SemanticSearchPropertiesTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(SemanticSearchConfiguration::class.java)

    @Test
    fun `unset, the query timeout is the spec's 5 s and no model is searched by meaning`() {
        runner.run { context ->
            val properties = context.getBean(SemanticSearchProperties::class.java)
            properties.queryTimeout shouldBe Duration.ofSeconds(5)
            properties.maxDistance shouldBe emptyMap()
        }
    }

    @Test
    fun `a threshold is set per model, by the model's name`() {
        runner
            .withPropertyValues("moyi.gratitude.semantic-search.max-distance.VOYAGE_3_5=0.4")
            .run { context ->
                context.getBean(SemanticSearchProperties::class.java).maxDistance shouldBe mapOf(EmbeddingSpace.VOYAGE_3_5 to 0.4)
            }
    }
}
