package com.moyi.identity.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** FR-008's two windows, as the domain states them: fourteen days to call it off, thirty to carry it out. */
internal class DeletionRequestTest {
    private val requestedAt = Instant.parse("2026-11-09T10:00:00Z")
    private val request = DeletionRequest.open(UUID.randomUUID(), UserId(UUID.randomUUID()), requestedAt)

    @Test
    fun `the cancellation window is fourteen days and the deadline thirty`() {
        request.cancellableUntil shouldBe requestedAt.plus(Duration.ofDays(14))
        request.deleteBy shouldBe requestedAt.plus(Duration.ofDays(30))
    }

    @Test
    fun `it can be cancelled up to the end of the window and not at it`() {
        request.canBeCancelledAt(requestedAt) shouldBe true
        request.canBeCancelledAt(request.cancellableUntil.minusNanos(1000)) shouldBe true
        request.canBeCancelledAt(request.cancellableUntil) shouldBe false
    }

    @Test
    fun `the instant is cut to microseconds, so it reads back as written`() {
        val fine = DeletionRequest.open(UUID.randomUUID(), UserId(UUID.randomUUID()), Instant.parse("2026-11-09T10:00:00.123456789Z"))
        fine.requestedAt shouldBe Instant.parse("2026-11-09T10:00:00.123456Z")
    }
}
