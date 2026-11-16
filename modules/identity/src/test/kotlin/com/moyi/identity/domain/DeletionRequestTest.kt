package com.moyi.identity.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
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

    @Test
    fun `the operator's SQL opens the same window, in hours, as this class`() {
        // V69 (admin module) writes execute_after itself, for deletion asked for by email. The job reads
        // execute_after and the app's cancel reads requestedAt + CANCELLATION_WINDOW, so the two must agree.
        val root =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").exists() }
        val sql =
            File(root, "modules/admin/src/main/resources/db/migration/V69__admin_operator_deletion_request.sql")
                .readLines()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
        val intervals = Regex("interval '([^']*)'").findAll(sql).map { it.groupValues[1] }.toList()

        intervals.size shouldBe 2
        intervals.forEach { it shouldBe "${DeletionRequest.CANCELLATION_WINDOW.toHours()} hours" }
        DeletionRequest.CANCELLATION_WINDOW shouldBe Duration.ofHours(336)
    }
}
