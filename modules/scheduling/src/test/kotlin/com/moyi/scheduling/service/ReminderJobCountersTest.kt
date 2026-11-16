package com.moyi.scheduling.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.gratitude.api.NudgeCounts
import com.moyi.gratitude.api.ReminderRun
import com.moyi.scheduling.infra.RecordingReminders
import com.moyi.scheduling.infra.SchedulingTestApplication
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant

/** What the reminder job counts of a run, by nudge index and outcome (issue #200). */
@SpringBootTest(classes = [SchedulingTestApplication::class])
internal class ReminderJobCountersTest(
    @Autowired private val job: ReminderJob,
    @Autowired private val reminders: RecordingReminders,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: MeterRegistry,
) : PostgresIntegrationTest() {
    @AfterEach
    fun clear() = reminders.reset()

    @Test
    fun `a run counts each nudge by index and outcome, and the total requested over every index`() {
        clock.set(Instant.parse("2026-10-28T20:00:30Z"))
        val before = Outcomes.entries.associateWith { (n, o) -> nudges(n, o) }
        val requestedBefore = meters.counter(ReminderJob.REQUESTED).count()
        reminders.next = {
            ReminderRun(
                due = 4,
                requested = 3,
                alreadyWritten = 0,
                failed = 0,
                byNudge = mapOf(1 to NudgeCounts(2, 1, 0, 0), 2 to NudgeCounts(1, 0, 0, 0)),
            )
        }

        job.remindOnce()

        (Outcomes.entries.associateWith { (n, o) -> nudges(n, o) - before.getValue(n to o) }) shouldBe
            mapOf(
                (1 to "requested") to 2.0,
                (1 to "written") to 1.0,
                (1 to "already_asked") to 0.0,
                (1 to "failed") to 0.0,
                (2 to "requested") to 1.0,
                (2 to "written") to 0.0,
                (2 to "already_asked") to 0.0,
                (2 to "failed") to 0.0,
                (3 to "requested") to 0.0,
                (3 to "written") to 0.0,
                (3 to "already_asked") to 0.0,
                (3 to "failed") to 0.0,
            )
        (meters.counter(ReminderJob.REQUESTED).count() - requestedBefore) shouldBe 3.0
    }

    private fun nudges(
        nudge: Int,
        outcome: String,
    ): Double = meters.counter(ReminderJob.NUDGES, "nudge", nudge.toString(), "outcome", outcome).count()

    private object Outcomes {
        val entries = listOf(1, 2, 3).flatMap { n -> listOf("requested", "written", "already_asked", "failed").map { n to it } }
    }
}
