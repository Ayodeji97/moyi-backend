package com.moyi.gratitude.service

import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.api.DateSettlement
import com.moyi.gratitude.api.DaySettlement
import com.moyi.gratitude.domain.LateEntryGrace
import com.moyi.gratitude.infra.GratitudeTestApplication
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * The date check (ADR-0067 decision 3): the days of one date counted by where the close job has
 * got with them, at the close job's own threshold (the grace, and the settle margin).
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
internal class DaySettlementTest(
    @Autowired private val settlement: DaySettlement,
    @Autowired private val grace: LateEntryGrace,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val date = LocalDate.of(2026, 9, 10)
    private val now = Instant.parse("2026-09-12T12:00:00Z")

    @BeforeEach
    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE entries, bond_days CASCADE")
    }

    @Test
    fun `each day of the date is counted where the close job has got with it, and another date's not at all`() {
        val threshold = CloseElapsedDays.settledBy(grace, now)
        day(endsAt = threshold.minusSeconds(1)) // open, past due
        day(endsAt = threshold) // open, past due: the candidate condition is "ended by"
        day(endsAt = threshold.plusSeconds(1)) // open, not yet due
        day(endsAt = threshold.plus(Duration.ofDays(1)), status = "PENDING_REVEAL") // a reveal: the next run takes it
        day(endsAt = threshold.minusSeconds(1), closed = true) // closed, not counted
        day(endsAt = threshold.minusSeconds(1), closed = true, evaluated = true) // done
        day(endsAt = threshold.minusSeconds(1), on = date.plusDays(1)) // another date

        settlement.on(date, now) shouldBe DateSettlement(open = 4, pastDue = 3, uncounted = 1)
        settlement.on(date.minusDays(1), now) shouldBe DateSettlement(0, 0, 0)
    }

    @Test
    fun `the threshold is the grace and the settle margin before now, not now`() {
        day(endsAt = now.minus(Duration.ofMinutes(30)))

        settlement.on(date, now).pastDue shouldBe if (grace.length.isZero) 1 else 0
        grace.length shouldBe Duration.ofHours(12)
    }

    private fun day(
        endsAt: Instant,
        status: String = "PARTIAL",
        closed: Boolean = false,
        evaluated: Boolean = false,
        on: LocalDate = date,
    ) {
        jdbc.update(
            """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, closed_at, created_at,
                                   evaluated_at, evaluated_as, evaluated_strict, freeze_applied)
            VALUES (?, ?, ?, ?, 'UTC', ?, ?, ?, now(), ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            on,
            if (closed) "SOLO" else status,
            Timestamp.from(endsAt.minus(Duration.ofDays(1))),
            Timestamp.from(endsAt),
            if (closed) Timestamp.from(now) else null,
            if (evaluated) Timestamp.from(now) else null,
            if (evaluated) "MISSED" else null,
            if (evaluated) false else null,
            if (evaluated) false else null,
        )
    }
}
