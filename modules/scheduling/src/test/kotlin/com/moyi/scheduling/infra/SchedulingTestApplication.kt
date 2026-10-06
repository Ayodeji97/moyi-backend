package com.moyi.scheduling.infra

import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyKeyStore
import com.moyi.gratitude.api.CloseResult
import com.moyi.gratitude.api.DayCloser
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/**
 * This module and nothing of `gratitude`'s insides: the closer is a
 * recording stand-in, because what is on trial here is when it is called and
 * what is counted, and `gratitude`'s own tests hold what it does.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.scheduling"])
internal class SchedulingTestApplication {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(start = Instant.parse("2026-09-15T23:00:00Z"))

    @Bean
    fun meters(): MeterRegistry = SimpleMeterRegistry()

    @Bean
    fun closer(): RecordingCloser = RecordingCloser()

    @Bean
    fun idempotencyKeyStore(jdbc: JdbcTemplate): IdempotencyKeyStore = IdempotencyKeyStore(jdbc)
}

internal class RecordingCloser : DayCloser {
    val calls = mutableListOf<Pair<Instant, Int>>()

    @Volatile
    var next: () -> CloseResult = { NOTHING }

    override fun closeElapsedDays(
        now: Instant,
        budget: Int,
    ): CloseResult {
        calls += now to budget
        return next()
    }

    fun reset() {
        calls.clear()
        next = { NOTHING }
    }

    companion object {
        val NOTHING =
            CloseResult(created = 0, closed = 0, revealed = 0, evaluated = 0, failed = 0, bondsChanged = emptySet(), backlog = false)
    }
}
