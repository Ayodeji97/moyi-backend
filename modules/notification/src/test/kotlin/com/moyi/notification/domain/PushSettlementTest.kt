package com.moyi.notification.domain

import com.moyi.notification.domain.SendResult.ACCEPTED
import com.moyi.notification.domain.SendResult.REJECTED
import com.moyi.notification.domain.SendResult.TOKEN_INVALID
import com.moyi.notification.domain.SendResult.UNAVAILABLE
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/** The two pure rules of the push consumer (spec §2.2): when a push is too late, and what a set of sends adds up to. */
internal class PushSettlementTest {
    private val staleAfter = Instant.parse("2026-10-28T23:00:00Z")

    @Test
    fun `a push is expired from its stale-after instant on, and not a microsecond before`() {
        PushSettlement.isExpired(staleAfter, now = staleAfter.minusNanos(1_000)) shouldBe false
        PushSettlement.isExpired(staleAfter, now = staleAfter) shouldBe true
        PushSettlement.isExpired(staleAfter, now = staleAfter.plusSeconds(1)) shouldBe true
    }

    @Test
    fun `one phone that took it makes the push sent, whatever the others said`() {
        // A retry would send it again to the phone that has it; push is a hint (doc 25:69).
        PushSettlement.settle(listOf(UNAVAILABLE, ACCEPTED, TOKEN_INVALID, REJECTED)) shouldBe Settlement.Sent
        PushSettlement.settle(listOf(ACCEPTED)) shouldBe Settlement.Sent
    }

    @Test
    fun `with nothing accepted, a provider that may yet take it is retried`() {
        PushSettlement.settle(listOf(TOKEN_INVALID, UNAVAILABLE, REJECTED)) shouldBe Settlement.Retry
        PushSettlement.settle(listOf(UNAVAILABLE)) shouldBe Settlement.Retry
    }

    @Test
    fun `with nothing accepted and nothing to retry, the push fails for good and says why in a word of ours`() {
        PushSettlement.settle(listOf(TOKEN_INVALID, REJECTED)) shouldBe Settlement.Failed(PushSettlement.REJECTED)
        PushSettlement.settle(listOf(TOKEN_INVALID, TOKEN_INVALID)) shouldBe Settlement.Failed(PushSettlement.NO_VALID_TOKEN)
        PushSettlement.settle(emptyList()) shouldBe Settlement.Failed(PushSettlement.NO_DEVICE)
    }
}
