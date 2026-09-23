package com.moyi.identity.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The rule as Kotlin states it. `UserPersistenceTest` pins the SQL that
 * applies it to the same numbers; if either drifts, one of the two fails.
 */
internal class LockoutPolicyTest {
    @Test
    fun `four failures earn no lock, five earn a minute`() {
        (1..4).forEach { LockoutPolicy.lockDurationFor(it) shouldBe null }
        LockoutPolicy.lockDurationFor(5) shouldBe Duration.ofMinutes(1)
    }

    @Test
    fun `each further failure doubles the lock`() {
        LockoutPolicy.lockDurationFor(6) shouldBe Duration.ofMinutes(2)
        LockoutPolicy.lockDurationFor(7) shouldBe Duration.ofMinutes(4)
        LockoutPolicy.lockDurationFor(8) shouldBe Duration.ofMinutes(8)
        LockoutPolicy.lockDurationFor(10) shouldBe Duration.ofMinutes(32)
    }

    @Test
    fun `the lock caps at an hour and stays there`() {
        // 2^6 minutes is 64, so the eleventh failure hits the cap; the
        // hundredth must not overflow past it.
        LockoutPolicy.lockDurationFor(11) shouldBe Duration.ofHours(1)
        LockoutPolicy.lockDurationFor(12) shouldBe Duration.ofHours(1)
        LockoutPolicy.lockDurationFor(100) shouldBe Duration.ofHours(1)
    }
}
