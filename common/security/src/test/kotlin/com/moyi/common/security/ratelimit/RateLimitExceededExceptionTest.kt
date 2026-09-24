package com.moyi.common.security.ratelimit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** `states.md` §1b: "States when to try again. Never an accusation." */
internal class RateLimitExceededExceptionTest {
    @Test
    fun `the detail announces the wait in whole, rounded-up units, and nothing about why`() {
        detailFor(Duration.ofSeconds(1)) shouldBe "Please wait a minute before trying again."
        detailFor(Duration.ofSeconds(59)) shouldBe "Please wait a minute before trying again."
        detailFor(Duration.ofSeconds(61)) shouldBe "Please wait 2 minutes before trying again."
        detailFor(Duration.ofMinutes(3)) shouldBe "Please wait 3 minutes before trying again."
        detailFor(Duration.ofMinutes(20)) shouldBe "Please wait 20 minutes before trying again."
        detailFor(Duration.ofMinutes(60)) shouldBe "Please wait an hour before trying again."
        detailFor(Duration.ofMinutes(61)) shouldBe "Please wait 2 hours before trying again."
    }

    @Test
    fun `Retry-After is whole seconds, rounded up, and never zero`() {
        retryAfterSecondsFor(Duration.ofMillis(1)) shouldBe 1
        retryAfterSecondsFor(Duration.ofMillis(1500)) shouldBe 2
        retryAfterSecondsFor(Duration.ofMinutes(3)) shouldBe 180
    }

    private fun detailFor(wait: Duration) = exception(wait).detail

    private fun retryAfterSecondsFor(wait: Duration) = exception(wait).retryAfterSeconds

    private fun exception(wait: Duration) =
        RateLimitExceededException(RateLimitDecision.Rejected(limit = 5, retryAfter = wait, resetAt = Instant.EPOCH))
}
