package com.moyi.notification.domain

import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.SharedChange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** Coalescing's rules over values: which change a push that superseded others carries (spec §10, ADR-0069 decision 7). */
internal class CoalescingTest {
    @Test
    fun `the same change superseded keeps its own`() {
        merged(SharedChange.SETTING, listOf(SharedChange.SETTING)) shouldBe SharedChange.SETTING
    }

    @Test
    fun `a different change superseded makes several`() {
        merged(SharedChange.SETTING, listOf(SharedChange.PROPOSAL_DECLINED)) shouldBe SharedChange.SEVERAL
    }

    @Test
    fun `nothing superseded keeps its own`() {
        merged(SharedChange.PROPOSAL_ACCEPTED, emptyList()) shouldBe SharedChange.PROPOSAL_ACCEPTED
    }

    @Test
    fun `several superseded stays several`() {
        merged(SharedChange.SETTING, listOf(SharedChange.SEVERAL)) shouldBe SharedChange.SEVERAL
    }

    @Test
    fun `one different among the same makes several`() {
        merged(SharedChange.SETTING, listOf(SharedChange.SETTING, SharedChange.PROPOSAL_WITHDRAWN, SharedChange.SETTING)) shouldBe
            SharedChange.SEVERAL
    }

    @Test
    fun `only a shared-setting push coalesces`() {
        PushCategory.entries.filter { it.coalesces } shouldBe listOf(PushCategory.SHARED_SETTING_CHANGED)
    }

    @Test
    fun `a coalescing push settles for two minutes`() {
        Coalescing.SETTLE shouldBe Duration.ofMinutes(2)
    }

    private fun merged(
        own: SharedChange,
        cancelled: List<SharedChange>,
    ): SharedChange = Coalescing.merged(own, cancelled, SharedChange.SEVERAL)
}
