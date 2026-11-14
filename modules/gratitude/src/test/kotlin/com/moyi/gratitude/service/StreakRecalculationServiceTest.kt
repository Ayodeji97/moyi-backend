package com.moyi.gratitude.service

import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.api.RebuildResult
import com.moyi.gratitude.api.StreakFigures
import com.moyi.gratitude.api.StreakRecalculation
import com.moyi.gratitude.infra.GratitudeTestApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * The admin's rebuild (FR-074, ADR-0067 decision 6) over `RecalculateStreak`, whose replay
 * `RecalculateStreakTest` holds: here, that the port joins a transaction or refuses, tells a drifted
 * row from a current one, and is never a way to make a row. The days are written evaluated, directly:
 * what is on trial is the port, not the close job.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
internal class StreakRecalculationServiceTest(
    @Autowired private val rebuild: StreakRecalculation,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val bond = UUID.randomUUID()

    @BeforeEach
    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute("TRUNCATE TABLE entries, bond_days CASCADE")
    }

    @Test
    fun `outside a transaction it refuses before reading anything`() {
        shouldThrow<IllegalTransactionStateException> { rebuild.rebuild(bond) }
    }

    @Test
    fun `a bond with evaluated days and a drifted row is put back, and a second rebuild changes nothing`() {
        (1..3).forEach { completeDay(it) }
        transactions.execute { rebuild.rebuild(bond) }
        jdbc.update("UPDATE streak_states SET current_streak = 9, longest_streak = 9, total_complete_days = 0 WHERE bond_id = ?", bond)

        val changed = transactions.execute { rebuild.rebuild(bond) }!!

        changed.result shouldBe RebuildResult.CHANGED
        changed.before shouldBe StreakFigures(current = 9, longest = 9, freezesAvailable = 0, completeDays = 0)
        changed.after shouldBe StreakFigures(current = 3, longest = 3, freezesAvailable = 0, completeDays = 3)
        jdbc.queryForObject("SELECT current_streak FROM streak_states WHERE bond_id = ?", Int::class.java, bond) shouldBe 3

        val again = transactions.execute { rebuild.rebuild(bond) }!!
        again.result shouldBe RebuildResult.UNCHANGED
        again.before shouldBe again.after
    }

    @Test
    fun `a rebuild rolled back with its transaction leaves the drifted row as it was`() {
        (1..2).forEach { completeDay(it) }
        transactions.execute { rebuild.rebuild(bond) }
        jdbc.update("UPDATE streak_states SET current_streak = 0 WHERE bond_id = ?", bond)

        transactions.execute { status ->
            rebuild.rebuild(bond).result shouldBe RebuildResult.CHANGED
            status.setRollbackOnly()
        }

        jdbc.queryForObject("SELECT current_streak FROM streak_states WHERE bond_id = ?", Int::class.java, bond) shouldBe 0
    }

    @Test
    fun `a bond with no evaluated day, or no bond at all, is NO_DAYS, and no row and no event is written`() {
        jdbc.update(
            "INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, closed_at, created_at) " +
                "VALUES (?, ?, DATE '2026-09-02', 'EMPTY', 'UTC', now(), now(), now(), now())",
            UUID.randomUUID(),
            bond,
        )

        listOf(bond, UUID.randomUUID()).forEach { id ->
            val result = transactions.execute { rebuild.rebuild(id) }!!
            result.result shouldBe RebuildResult.NO_DAYS
            result.before shouldBe null
            result.after shouldBe null
        }
        jdbc.queryForObject("SELECT count(*) FROM streak_states", Int::class.java) shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java) shouldBe 0
    }

    private fun completeDay(n: Long) {
        val date = LocalDate.of(2026, 9, 1).plusDays(n)
        jdbc.update(
            """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, closed_at, created_at,
                                   evaluated_at, evaluated_as, evaluated_strict, freeze_applied)
            VALUES (?, ?, ?, 'REVEALED', 'UTC', ?::date, ?::date + 1, 2, now(), now(), now(), 'COMPLETE', false, false)
            """.trimIndent(),
            UUID.randomUUID(),
            bond,
            date,
            date.toString(),
            date.toString(),
        )
    }

    private fun completeDay(n: Int) = completeDay(n.toLong())
}
