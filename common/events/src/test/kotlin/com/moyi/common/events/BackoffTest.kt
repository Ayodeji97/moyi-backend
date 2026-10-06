package com.moyi.common.events

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

internal class BackoffTest {
    @Test
    fun `the first failure waits two seconds and each one after it twice as long`() {
        Backoff.delayAfter(1) shouldBe Duration.ofSeconds(2)
        Backoff.delayAfter(2) shouldBe Duration.ofSeconds(4)
        Backoff.delayAfter(3) shouldBe Duration.ofSeconds(8)
        Backoff.delayAfter(9) shouldBe Duration.ofSeconds(512)
    }

    @Test
    fun `the wait stops growing at fifteen minutes`() {
        // 2 s doubled nine times is 1024 s, the first step past the cap.
        Backoff.delayAfter(10) shouldBe Duration.ofMinutes(15)
        Backoff.delayAfter(11) shouldBe Duration.ofMinutes(15)
    }

    @Test
    fun `an attempt count far past the cap does not overflow into a short or negative wait`() {
        // There is no last attempt, so the count is unbounded: 2^63 and beyond must still be the cap.
        listOf(31, 32, 33, 62, 63, 64, 65, 1000, Int.MAX_VALUE).forEach { attempts ->
            Backoff.delayAfter(attempts) shouldBe Duration.ofMinutes(15)
        }
    }

    @Test
    fun `a count below one is treated as the first failure, because the failure path must not itself throw`() {
        Backoff.delayAfter(0) shouldBe Duration.ofSeconds(2)
        Backoff.delayAfter(Int.MIN_VALUE) shouldBe Duration.ofSeconds(2)
    }
}
