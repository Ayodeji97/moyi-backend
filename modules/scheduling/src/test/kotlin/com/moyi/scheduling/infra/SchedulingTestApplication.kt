package com.moyi.scheduling.infra

import com.moyi.common.core.IdGenerator
import com.moyi.common.core.SystemIdGenerator
import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
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
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * This module and nothing of `gratitude`'s insides: the closer is a
 * recording stand-in, because what is on trial here is when it is called and
 * what is counted, and `gratitude`'s own tests hold what it does.
 *
 * The outbox is the real one (`com.moyi.common.events`, against the real
 * database), with one consumer of this context's own: the poller is on trial
 * for what it delivers and what it meters, and that needs something delivered.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.scheduling", "com.moyi.common.events"])
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

    /** What the outbox's publisher needs and this context does not otherwise have. */
    @Bean
    fun ids(clock: Clock): IdGenerator = SystemIdGenerator(clock)

    @Bean
    fun mapper(): ObjectMapper = JsonMapper.builder().build()

    @Bean
    fun consumer(): RecordingConsumer = RecordingConsumer()
}

/**
 * The one consumer this context registers, at start-up, as the application
 * registers its own. Its id and event type belong to this module's tests, so
 * what it is owed is only what they publish.
 */
internal class RecordingConsumer : EventConsumer {
    override val id = ID
    override val eventTypes = setOf(EVENT_TYPE)
    override val startFrom = StartFrom.NOW

    val received = CopyOnWriteArrayList<ReceivedEvent>()

    @Volatile
    var next: (ReceivedEvent) -> Unit = {}

    override fun handle(event: ReceivedEvent) {
        received += event
        next(event)
    }

    fun reset() {
        received.clear()
        next = {}
    }

    companion object {
        const val ID = "test.scheduling.outbox"
        const val EVENT_TYPE = "SchedulingOutboxTest"
    }
}

internal class RecordingCloser : DayCloser {
    /** Written by whichever thread runs the job; under [com.moyi.scheduling.service.PollerIndependenceTest] that is the timer's. */
    val calls = CopyOnWriteArrayList<Pair<Instant, Int>>()

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
