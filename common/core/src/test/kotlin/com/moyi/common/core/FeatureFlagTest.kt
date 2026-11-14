package com.moyi.common.core

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test

/** ADR-0067 decisions 7 and 8: three flags, each on until switched, each saying what "off" pauses. */
class FeatureFlagTest {
    @Test
    fun `the three flags, each on by default and each saying what it pauses`() {
        FeatureFlag.entries.map { it.name } shouldContainExactly listOf("DAILY_REMINDERS", "ON_THIS_DAY_PUSHES", "AI_EMBEDDINGS")
        FeatureFlag.entries.forEach {
            it.defaultOn shouldBe true
            it.pauses.shouldNotBeBlank()
        }
    }

    @Test
    fun `a FeatureFlags is the one question it answers`() {
        val off = FeatureFlags { it != FeatureFlag.AI_EMBEDDINGS }

        off.isOn(FeatureFlag.AI_EMBEDDINGS) shouldBe false
        off.isOn(FeatureFlag.DAILY_REMINDERS) shouldBe true
    }
}
