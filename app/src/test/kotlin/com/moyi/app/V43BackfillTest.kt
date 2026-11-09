package com.moyi.app

import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.LocalDate
import java.util.UUID

/**
 * V43 runs on a database whose streaks have already been evaluated: each `streak_states` row
 * gains the run its bond's **latest** break ended, read from that break's line in
 * `streak_events` (issue #141, ADR-0034 as amended 2026-11-08), and a bond that never broke a
 * run gains nothing.
 *
 * As [V25BackfillTest]: a database of its own in the shared container, taken to V41 by Flyway
 * (V42 is another branch's), given rows, then taken to V43. No Spring context: what is tested is
 * the migration as Flyway runs it in production, against rows written before it.
 */
class V43BackfillTest : PostgresIntegrationTest() {
    private val admin = JdbcTemplate(source("test"))

    @AfterEach
    fun drop() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
    }

    @Test
    fun `V43 gives each streak the run its latest break ended, and a streak that never broke none`() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
        admin.execute("CREATE DATABASE $DATABASE")
        val target = source(DATABASE)
        flyway(target, "41").migrate()
        val jdbc = JdbcTemplate(target)

        // Two breaks and a new run after them. The first break ended the longer run, so a backfill
        // that took the first, or the longest, would say ten.
        val twice = UUID.randomUUID()
        state(jdbc, twice, current = 2, longest = 10)
        event(jdbc, twice, day(10), "BROKEN", before = 10, after = 0)
        event(jdbc, twice, day(13), "BROKEN", before = 3, after = 0)
        event(jdbc, twice, day(14), "EXTENDED", before = 0, after = 1)
        event(jdbc, twice, day(15), "EXTENDED", before = 1, after = 2)
        // Never broken: a run, a freeze banked and spent.
        val never = UUID.randomUUID()
        state(jdbc, never, current = 15, longest = 15)
        event(jdbc, never, day(14), "FREEZE_BANKED", before = 14, after = 14)
        event(jdbc, never, day(15), "FREEZE_CONSUMED", before = 14, after = 15)
        // Broken, and nothing written since: the streak reads zero.
        val zero = UUID.randomUUID()
        state(jdbc, zero, current = 0, longest = 5)
        event(jdbc, zero, day(5), "EXTENDED", before = 4, after = 5)
        event(jdbc, zero, day(6), "BROKEN", before = 5, after = 0)

        flyway(target, "43").migrate()

        lastRun(jdbc, twice) shouldBe (3 to day(13))
        lastRun(jdbc, never) shouldBe (null to null)
        lastRun(jdbc, zero) shouldBe (5 to day(6))
        // The CHECK V43 adds: a length and a date or neither, the length within the longest.
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("UPDATE streak_states SET last_run_broken_on = NULL WHERE bond_id = ?", twice)
        }
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("UPDATE streak_states SET last_run_length = 11 WHERE bond_id = ?", twice)
        }
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("UPDATE streak_states SET last_run_length = 0 WHERE bond_id = ?", twice)
        }
    }

    private fun lastRun(
        jdbc: JdbcTemplate,
        bond: UUID,
    ): Pair<Int?, LocalDate?> =
        jdbc.queryForObject(
            "SELECT last_run_length, last_run_broken_on FROM streak_states WHERE bond_id = ?",
            { row, _ -> (row.getInt(1).takeUnless { row.wasNull() }) to row.getObject(2, LocalDate::class.java) },
            bond,
        )!!

    private fun state(
        jdbc: JdbcTemplate,
        bond: UUID,
        current: Int,
        longest: Int,
    ) {
        jdbc.update(
            "INSERT INTO streak_states (bond_id, current_streak, longest_streak, updated_at) VALUES (?, ?, ?, now())",
            bond,
            current,
            longest,
        )
    }

    @Suppress("LongParameterList") // A row's own columns.
    private fun event(
        jdbc: JdbcTemplate,
        bond: UUID,
        date: LocalDate,
        event: String,
        before: Int,
        after: Int,
    ) {
        jdbc.update(
            "INSERT INTO streak_events (id, bond_id, date, event, streak_before, streak_after, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, now())",
            UUID.randomUUID(),
            bond,
            date,
            event,
            before,
            after,
        )
    }

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n.toLong())

    private fun flyway(
        target: DriverManagerDataSource,
        version: String,
    ): Flyway =
        Flyway
            .configure()
            .dataSource(target)
            .locations("classpath:db/migration")
            .target(version)
            .load()

    private fun source(database: String): DriverManagerDataSource {
        val url = postgres.jdbcUrl
        val base = url.substringBefore('?').substringBeforeLast('/')
        val options = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        return DriverManagerDataSource("$base/$database$options", postgres.username, postgres.password)
    }

    private companion object {
        const val DATABASE = "moyi_v43_backfill"
    }
}
