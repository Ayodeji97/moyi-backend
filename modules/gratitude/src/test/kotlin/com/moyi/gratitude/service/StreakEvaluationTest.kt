package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The close job's third step (spec §6.4, §6.5), through the port the job
 * calls: days are written by two people's requests, the job is run with an
 * instant, and what is asserted is the bond's `streak_states` row, the
 * decision recorded on each day, and the events.
 *
 * `StreakRulesTest` and `StreakPropertiesTest` hold the rules. This holds
 * that the job feeds them the right days, in the right order, once.
 *
 * All in `Africa/Lagos` unless a test says otherwise: the day dated *d* ends
 * at 23:00Z on *d*, and the job settles it a minute later. Day 1 is the
 * bond's first full day, 2026-09-02.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(StreakEvaluationTest.TimeConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class StreakEvaluationTest(
    @Autowired private val closer: DayCloser,
    @Autowired private val streaks: EvaluateStreaks,
    @Autowired private val closeDay: CloseDay,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val pool = Executors.newFixedThreadPool(2)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String

    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        bond = pair(ada, bea, "Africa/Lagos", at = "2026-09-01T10:00:00Z")
    }

    /** Before as well as after ([setUp] calls it first): the job works on every bond in a shared database. */
    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute(
            "TRUNCATE TABLE idempotency_keys, entries, bond_days, bond_anchor_intervals, bond_proposals, " +
                "blocks, bond_invites, bond_members, bonds CASCADE",
        )
        users.clear()
    }

    @AfterAll
    fun stop() {
        pool.shutdownNow()
    }

    // --- the run ---

    @Test
    fun `each day both wrote on extends the run by one, with a line in the log and an event`() {
        (1..3).forEach { bothWriteOn(it) }

        closeThrough(3)

        streak() shouldBe Streak(current = 3, longest = 3, lastComplete = day(3), freezes = 0, progress = 3, consumed = 0, total = 3)
        decisions() shouldBe
            listOf(
                // Day 0, the day they paired: nobody wrote. A missed day on a run at zero moves nothing.
                Decision(day(0), "EMPTY", "MISSED", freeze = false),
                Decision(day(1), "REVEALED", "COMPLETE", freeze = false),
                Decision(day(2), "REVEALED", "COMPLETE", freeze = false),
                Decision(day(3), "REVEALED", "COMPLETE", freeze = false),
            )
        streakEvents() shouldBe listOf("EXTENDED 0>1", "EXTENDED 1>2", "EXTENDED 2>3")
        outbox("StreakExtended") shouldBe 3
        outbox("StreakBroken") shouldBe 0
    }

    @Test
    fun `a day nobody wrote on ends the run, and the longest run stands`() {
        (1..3).forEach { bothWriteOn(it) }

        closeThrough(4)

        streak() shouldBe Streak(current = 0, longest = 3, lastComplete = day(3), freezes = 0, progress = 3, consumed = 0, total = 3)
        decisions().last() shouldBe Decision(day(4), "EMPTY", "MISSED", freeze = false)
        streakEvents().last() shouldBe "BROKEN 3>0"
        outbox("StreakBroken") shouldBe 1
    }

    @Test
    fun `a missed day on a run already at zero is not a second break`() {
        bothWriteOn(1)

        closeThrough(4)

        streak().current shouldBe 0
        streakEvents() shouldBe listOf("EXTENDED 0>1", "BROKEN 1>0")
        outbox("StreakBroken") shouldBe 1
    }

    // --- freezes ---

    @Test
    fun `the fourteenth complete day banks a freeze, and the missed day after it is FROZEN with the run kept`() {
        (1..14).forEach { bothWriteOn(it) }
        closeThrough(14)
        streak().let { (it.freezes to it.progress) shouldBe (1 to 0) }
        streakEvents().takeLast(2) shouldBe listOf("EXTENDED 13>14", "FREEZE_BANKED 13>14")

        // Day 15: only Ada writes.
        val adas = writeOn(15, ada)
        closeThrough(15)

        streak() shouldBe Streak(current = 15, longest = 15, lastComplete = day(14), freezes = 0, progress = 0, consumed = 1, total = 14)
        // The day was SOLO for the instant between its closing and its evaluation; a freeze makes it FROZEN.
        decisions().last() shouldBe Decision(day(15), "FROZEN", "MISSED", freeze = true)
        streakEvents().last() shouldBe "FREEZE_CONSUMED 14>15"
        // The run grew, and nobody is told so: an announcement of day 15 would celebrate a day one of them missed.
        outbox("StreakExtended") shouldBe 14
        // Spec §4: "applying a freeze to a solo day ... must not hide previously revealed words".
        jdbc.queryForObject("SELECT revealed_at IS NOT NULL FROM entries WHERE id = ?::uuid", Boolean::class.java, adas) shouldBe true
    }

    @Test
    fun `with no freeze left the next missed day ends the run`() {
        (1..14).forEach { bothWriteOn(it) }

        closeThrough(16)

        streak().let { (it.current to it.longest) shouldBe (0 to 15) }
        decisions().takeLast(2) shouldBe
            listOf(Decision(day(15), "FROZEN", "MISSED", freeze = true), Decision(day(16), "EMPTY", "MISSED", freeze = false))
    }

    @Test
    fun `in Strict mode the fourteenth day banks nothing, and a missed day ends the run`() {
        strictMode(true)
        (1..14).forEach { bothWriteOn(it) }

        closeThrough(15)

        streak() shouldBe Streak(current = 0, longest = 14, lastComplete = day(14), freezes = 0, progress = 0, consumed = 0, total = 14)
        jdbc.queryForObject("SELECT bool_and(evaluated_strict) FROM bond_days WHERE bond_id = ?::uuid", Boolean::class.java, bond) shouldBe
            true
    }

    /** FR-073: "toggling Strict mode never alters past days". A freeze banked before is kept, and not spent while it is on. */
    @Test
    fun `Strict mode switched on after a freeze was banked spends none, and the freeze is still there`() {
        (1..14).forEach { bothWriteOn(it) }
        closeThrough(14)
        strictMode(true)

        closeThrough(15)

        streak().let { Triple(it.current, it.freezes, it.consumed) shouldBe Triple(0, 1, 0) }
        decisions().last() shouldBe Decision(day(15), "EMPTY", "MISSED", freeze = false)
    }

    /**
     * FR-073, for the gap between a day ending and the job judging it. Either
     * member can change Strict mode alone, so a day judged by the setting at
     * evaluation could be rescued after the fact: miss a day in Strict mode,
     * switch it off before the job runs, and have a freeze spent on it.
     */
    @Test
    fun `a day is judged by the Strict mode it ended under, not the one in force when the job ran`() {
        (1..14).forEach { bothWriteOn(it) }
        closeThrough(14)
        clock.set(at(15))
        strictMode(true)
        // Day 15 ends at 23:00Z with nobody having written. Two hours later, before any job has run, it is switched off.
        clock.set(Instant.parse("${day(16)}T01:00:00Z"))
        strictMode(false)

        closer.closeElapsedDays(Instant.parse("${day(16)}T02:00:00Z"), BUDGET).failed shouldBe 0

        decisions().last() shouldBe Decision(day(15), "EMPTY", "MISSED", freeze = false)
        streak().let { Triple(it.current, it.freezes, it.consumed) shouldBe Triple(0, 1, 0) }
        jdbc.queryForObject(
            "SELECT evaluated_strict FROM bond_days WHERE bond_id = ?::uuid AND date = ?",
            Boolean::class.java,
            bond,
            day(15),
        ) shouldBe true
    }

    /**
     * What one "last changed at" could not hold: off and on again, both
     * after the day ended and before the job ran. The last change alone says
     * "it was not strict before this"; the day ended in Strict mode.
     */
    @Test
    fun `switching Strict mode off and on again after a day ended does not rescue the day`() {
        (1..14).forEach { bothWriteOn(it) }
        closeThrough(14)
        clock.set(at(15))
        strictMode(true)
        clock.set(Instant.parse("${day(16)}T01:00:00Z"))
        strictMode(false)
        clock.set(Instant.parse("${day(16)}T01:00:05Z"))
        strictMode(true)

        closer.closeElapsedDays(Instant.parse("${day(16)}T02:00:00Z"), BUDGET).failed shouldBe 0

        decisions().last() shouldBe Decision(day(15), "EMPTY", "MISSED", freeze = false)
        streak().let { Triple(it.current, it.freezes, it.consumed) shouldBe Triple(0, 1, 0) }
    }

    /**
     * Two changes can carry one instant. Kept as one row holding the later
     * value, "on then off" would leave a row saying *off from here* on a bond
     * that was never on, and every earlier day would read as strict.
     */
    @Test
    fun `Strict mode switched on and off again at one instant changes nothing about the days before`() {
        (1..14).forEach { bothWriteOn(it) }
        closeThrough(14)
        clock.set(Instant.parse("${day(16)}T01:00:00Z"))
        strictMode(true)
        strictMode(false)

        closer.closeElapsedDays(Instant.parse("${day(16)}T02:00:00Z"), BUDGET).failed shouldBe 0

        // Day 15 ended with Strict mode off, as it had always been: the banked freeze covers it.
        decisions().last() shouldBe Decision(day(15), "FROZEN", "MISSED", freeze = true)
        jdbc.queryForObject("SELECT count(*) FROM bond_strict_mode_changes", Int::class.java) shouldBe 0
    }

    @Test
    fun `days whose bond row is gone are counted as a failure and said, not passed over`(output: CapturedOutput) {
        bothWriteOn(0)
        closeDay.settle(dayId(0), settles(0)) shouldBe CloseDay.Outcome.CLOSED
        jdbc.update("DELETE FROM bonds WHERE id = ?::uuid", bond)

        val result = streaks.evaluate()

        result.failed shouldBe 1
        result.days shouldBe 0
        output.all shouldContain "streak: bond $bond has days to evaluate and no bond row"
        jdbc.queryForObject("SELECT count(*) FROM streak_states", Int::class.java) shouldBe 0
    }

    @Test
    fun `a freeze is not spent on a day missed with no run to save`() {
        closeThrough(0)
        // A freeze in hand and a run of zero: what a couple has after a break in Strict mode.
        jdbc.update("UPDATE streak_states SET freezes_available = 1 WHERE bond_id = ?::uuid", bond)

        closeThrough(1)

        decisions().last() shouldBe Decision(day(1), "EMPTY", "MISSED", freeze = false)
        streak().let { Triple(it.current, it.freezes, it.consumed) shouldBe Triple(0, 1, 0) }
        streakEvents() shouldBe emptyList()
    }

    // --- days that move nothing ---

    @Test
    fun `days from before the bond was two people are skipped, and the run starts with the first shared day`() {
        clear()
        val cara = users.verified("Cara")
        val dan = users.verified("Dan")
        clock.set(at(1))
        val created = createBond(cara, "Africa/Lagos")
        bond = idOf(created)
        // Cara writes alone on days 1 and 3, and not on 2: three days as one person.
        writeOn(1, cara)
        writeOn(3, cara)
        clock.set(at(4))
        accept(dan, codeOf(created))
        bothWriteOn(4, cara, dan)
        bothWriteOn(5, cara, dan)

        closeThrough(5)

        streak().let { (it.current to it.total) shouldBe (2 to 2) }
        decisions() shouldBe
            listOf(
                Decision(day(1), "SUSPENDED", "SUSPENDED", freeze = false),
                Decision(day(3), "SUSPENDED", "SUSPENDED", freeze = false),
                Decision(day(4), "REVEALED", "COMPLETE", freeze = false),
                Decision(day(5), "REVEALED", "COMPLETE", freeze = false),
            )
    }

    /** BR-6: a date a mutually confirmed zone change stepped over is not a day the couple missed. */
    @Test
    fun `a date an eastward zone change stepped over keeps the run going`() {
        clear()
        val cara = users.verified("Cara")
        val dan = users.verified("Dan")
        bond = pair(cara, dan, "Pacific/Pago_Pago", at = "2026-09-13T12:00:00Z")
        // Pago Pago's 14th and 15th, both written on.
        writeAt("2026-09-14T20:00:00Z", cara, dan)
        writeAt("2026-09-15T19:00:00Z", cara, dan)
        clock.set(Instant.parse("2026-09-15T20:00:00Z"))
        changeZone(cara, dan, "Pacific/Kiritimati")
        // The handoff is 11:00Z on the 16th; in Kiritimati that is already the 17th. Both write on it.
        writeAt("2026-09-16T20:00:00Z", cara, dan)

        // Kiritimati's 17th ends at 10:00Z on the 17th.
        closer.closeElapsedDays(Instant.parse("2026-09-17T10:01:00Z"), BUDGET).failed shouldBe 0

        decisions().takeLast(4).map { "${it.date} ${it.status} ${it.evaluatedAs}" } shouldBe
            listOf(
                "2026-09-14 REVEALED COMPLETE",
                "2026-09-15 REVEALED COMPLETE",
                "2026-09-16 FROZEN FROZEN_BY_SKIP",
                "2026-09-17 REVEALED COMPLETE",
            )
        streak().let { Triple(it.current, it.total, it.consumed) shouldBe Triple(4, 3, 0) }
        // Three days were written on. The date nobody could have written on is not announced.
        outbox("StreakExtended") shouldBe 3
    }

    /** Doc 04 §8.3: when a bond ends "the streak freezes rather than breaks — it is preserved at its value". */
    @Test
    fun `the day a bond ended on does not end its streak`() {
        (1..3).forEach { bothWriteOn(it) }
        writeOn(4, ada)
        clock.set(at(4).plusSeconds(3_600))
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204

        closeThrough(6)

        streak().let { (it.current to it.longest) shouldBe (3 to 3) }
        decisions().last() shouldBe Decision(day(4), "SOLO", "AFTER_THE_END", freeze = false)
        outbox("StreakBroken") shouldBe 0
    }

    /**
     * §8.3 protects a streak from a break; it does not take a day from it.
     * Both wrote in the morning and were shown a streak of four; one left in
     * the afternoon. The day they completed is in the record they keep.
     */
    @Test
    fun `a day both wrote on before the bond ended that day is counted`() {
        (1..3).forEach { bothWriteOn(it) }
        bothWriteOn(4)
        clock.set(at(4).plusSeconds(3_600))
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204

        closeThrough(6)

        streak() shouldBe Streak(current = 4, longest = 4, lastComplete = day(4), freezes = 0, progress = 4, consumed = 0, total = 4)
        decisions().last() shouldBe Decision(day(4), "REVEALED", "COMPLETE", freeze = false)
    }

    // --- what is written down ---

    /** Spec §8: events carry ids only. FR-076: nothing in one says who wrote, or that anybody did not. */
    @Test
    fun `a streak event carries the bond's id and nothing else`() {
        bothWriteOn(1)

        closeThrough(2)

        jdbc.queryForList(
            "SELECT event_type || ' ' || payload::text FROM outbox_events WHERE event_type LIKE 'Streak%' ORDER BY event_type",
            String::class.java,
        ) shouldBe listOf("""StreakBroken {"bondId": "$bond"}""", """StreakExtended {"bondId": "$bond"}""")
    }

    /** The audit log is append-only and says a thing once: a second evaluation of a day cannot add to it. */
    @Test
    fun `the audit log refuses a second line for one day's one change`() {
        bothWriteOn(1)
        closeThrough(1)

        shouldThrow<DuplicateKeyException> {
            jdbc.update(
                "INSERT INTO streak_events (id, bond_id, date, event, streak_before, streak_after, created_at) " +
                    "SELECT gen_random_uuid(), bond_id, date, event, streak_before, streak_after, created_at FROM streak_events",
            )
        }
    }

    // --- order: the job settles days out of order, and the streak is a fold ---

    @Test
    fun `a backlog evaluated in one run gives the streak the same days give a night at a time`() {
        val pattern = listOf(1, 2, 4, 5, 6)
        pattern.forEach { bothWriteOn(it) }

        // One run, days after the last of them.
        closer.closeElapsedDays(settles(7), BUDGET).failed shouldBe 0
        val atOnce = streak() to decisions()

        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        bond = pair(ada, bea, "Africa/Lagos", at = "2026-09-01T10:00:00Z")
        pattern.forEach { bothWriteOn(it) }
        // The same days were all written before any close, so replay the job once per night.
        (1..7).forEach { closer.closeElapsedDays(settles(it), BUDGET).failed shouldBe 0 }

        (streak() to decisions()) shouldBe atOnce
        atOnce.first.let { Triple(it.current, it.longest, it.total) shouldBe Triple(0, 3, 5) }
    }

    /**
     * Step 2 closes a day that has a row; step 1 wrote the days that had
     * none. If a day in between is not yet written, the days after it wait:
     * evaluated now, they would be counted as if it had not happened.
     */
    @Test
    fun `a day that follows one not yet written is not evaluated until that one is`() {
        bothWriteOn(1)
        closeThrough(1)
        bothWriteOn(3)
        // Day 3 is closed on its own, as a run whose allowance for unwritten days had run out would leave it.
        closeDay.settle(dayId(3), settles(3)) shouldBe CloseDay.Outcome.CLOSED

        streaks.evaluate().days shouldBe 0
        streak().current shouldBe 1

        // The next run writes day 2 and the fold goes on from where it stopped.
        closer.closeElapsedDays(settles(3), BUDGET).evaluated shouldBe 2
        streak().let { (it.current to it.longest) shouldBe (1 to 1) }
        streakEvents() shouldBe listOf("EXTENDED 0>1", "BROKEN 1>0", "EXTENDED 0>1")
    }

    @Test
    fun `a day that cannot be closed holds back the days after it, and no other bond`(output: CapturedOutput) {
        val (cara, dan) = users.verified("Cara") to users.verified("Dan")
        val other = pair(cara, dan, "Africa/Lagos", at = "2026-09-01T10:00:00Z")
        bothWriteOn(1)
        val stuck = writeOn(2, ada)
        bothWriteOn(3)
        (1..3).forEach { d -> bothWriteOn(d, cara, dan, into = other) }
        // Closing a one-entry day reveals its entry; this forbids that for one entry only.
        jdbc.execute("ALTER TABLE entries ADD CONSTRAINT c4_stuck_probe CHECK (revealed_at IS NULL OR id <> '$stuck')")
        try {
            closer.closeElapsedDays(settles(3), BUDGET).failed shouldBe 1

            // Day 2 would not close, so day 3 — closed, and complete — is not counted yet.
            streak().current shouldBe 1
            decisions().map { it.date } shouldBe listOf(day(0), day(1))
            streakOf(other).current shouldBe 3
            output.all shouldContain "close: day"
        } finally {
            jdbc.execute("ALTER TABLE entries DROP CONSTRAINT c4_stuck_probe")
        }

        closer.closeElapsedDays(settles(3), BUDGET).failed shouldBe 0
        streakEvents() shouldBe listOf("EXTENDED 0>1", "BROKEN 1>0", "EXTENDED 0>1")
    }

    @Test
    fun `one bond whose streak cannot be evaluated is counted and left, and the others are evaluated`(output: CapturedOutput) {
        val (cara, dan) = users.verified("Cara") to users.verified("Dan")
        val other = pair(cara, dan, "Africa/Lagos", at = "2026-09-01T10:00:00Z")
        bothWriteOn(0)
        bothWriteOn(0, cara, dan, into = other)
        closeDay.settle(dayId(0), settles(0)) shouldBe CloseDay.Outcome.CLOSED
        closeDay.settle(dayId(0, other), settles(0)) shouldBe CloseDay.Outcome.CLOSED
        // Its calendar gone, this bond's days cannot be placed.
        jdbc.update("DELETE FROM bond_anchor_intervals WHERE bond_id = ?::uuid", bond)

        val result = streaks.evaluate()

        result.failed shouldBe 1
        result.days shouldBe 1
        streakOf(other).current shouldBe 1
        jdbc.queryForObject(
            "SELECT count(*) FROM bond_days WHERE bond_id = ?::uuid AND evaluated_at IS NOT NULL",
            Int::class.java,
            bond,
        ) shouldBe
            0
        output.all shouldContain "streak: bond $bond could not be evaluated"
    }

    @Test
    fun `two runs evaluating one bond at once count each day once`() {
        (0..2).forEach { bothWriteOn(it) }
        (0..2).forEach { closeDay.settle(dayId(it), settles(2)) shouldBe CloseDay.Outcome.CLOSED }
        jdbc.update("INSERT INTO streak_states (bond_id, updated_at) VALUES (?::uuid, now())", bond)

        val evaluated =
            dataSource.connection.use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("SELECT 1 FROM streak_states WHERE bond_id = '$bond' FOR UPDATE") }
                val runs = List(2) { pool.submit(Callable { streaks.evaluate() }) }
                try {
                    await().atMost(Duration.ofSeconds(10)).until {
                        jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'",
                            Int::class.java,
                        )!! >= 2
                    }
                } finally {
                    holder.rollback()
                }
                runs.map { it.get(15, TimeUnit.SECONDS) }
            }

        // One run did the work and the other found none left. Without the
        // lock the second does not count the days twice either — each day's
        // record refuses a second write — but it fails, and a failure here
        // is a bond left for the next run for no reason.
        evaluated.map { it.days }.sorted() shouldBe listOf(0, 3)
        evaluated.map { it.failed } shouldBe listOf(0, 0)
        streak().let { (it.current to it.total) shouldBe (3 to 3) }
        streakEvents().size shouldBe 3
    }

    /**
     * The evaluation takes the bond's lock first, as the closer does: a
     * change of Strict mode that is still committing is then in, with its
     * stamp, before a day is judged by it. Held here as a member's request
     * would hold it; the evaluation waits, and finishes once it is released.
     */
    @Test
    fun `an evaluation waits for a write to its bond that is still committing`() {
        bothWriteOn(0)
        closeDay.settle(dayId(0), settles(0)) shouldBe CloseDay.Outcome.CLOSED

        val evaluated =
            dataSource.connection.use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("SELECT 1 FROM bonds WHERE id = '$bond' FOR UPDATE") }
                val run = pool.submit(Callable { streaks.evaluate() })
                try {
                    await().atMost(Duration.ofSeconds(10)).until {
                        jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'",
                            Int::class.java,
                        )!! >= 1
                    }
                    // Blocked, and nothing judged yet.
                    jdbc.queryForObject("SELECT count(*) FROM bond_days WHERE evaluated_at IS NOT NULL", Int::class.java) shouldBe 0
                } finally {
                    holder.rollback()
                }
                run.get(15, TimeUnit.SECONDS)
            }

        evaluated.days shouldBe 1
        streak().current shouldBe 1
    }

    // ---- what is read back ----------------------------------------------

    private data class Streak(
        val current: Int,
        val longest: Int,
        val lastComplete: LocalDate?,
        val freezes: Int,
        val progress: Int,
        val consumed: Int,
        val total: Int,
    )

    private data class Decision(
        val date: LocalDate,
        val status: String,
        val evaluatedAs: String?,
        val freeze: Boolean?,
    )

    private fun streak(): Streak = streakOf(bond)

    private fun streakOf(bondId: String): Streak =
        jdbc
            .query("SELECT * FROM streak_states WHERE bond_id = ?::uuid", { row, _ ->
                Streak(
                    row.getInt("current_streak"),
                    row.getInt("longest_streak"),
                    row.getObject("last_complete_date", LocalDate::class.java),
                    row.getInt("freezes_available"),
                    row.getInt("freeze_progress"),
                    row.getInt("freezes_consumed"),
                    row.getInt("total_complete_days"),
                )
            }, bondId)
            .single()

    /** Every evaluated day of the bond, oldest first, with what was decided. */
    private fun decisions(): List<Decision> =
        jdbc.query(
            "SELECT date, status, evaluated_as, freeze_applied FROM bond_days " +
                "WHERE bond_id = ?::uuid AND evaluated_at IS NOT NULL ORDER BY date",
            { row, _ ->
                Decision(
                    row.getObject("date", LocalDate::class.java),
                    row.getString("status"),
                    row.getString("evaluated_as"),
                    row.getObject("freeze_applied") as Boolean?,
                )
            },
            bond,
        )

    private fun streakEvents(): List<String> =
        jdbc.query(
            "SELECT event || ' ' || streak_before || '>' || streak_after FROM streak_events WHERE bond_id = ?::uuid ORDER BY date, event",
            { row, _ -> row.getString(1) },
            bond,
        )

    private fun outbox(type: String): Int? =
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Int::class.java, type)

    private fun dayId(
        day: Int,
        bondId: String = bond,
    ): BondDayId =
        BondDayId(
            jdbc.queryForObject(
                "SELECT id FROM bond_days WHERE bond_id = ?::uuid AND date = ?",
                UUID::class.java,
                bondId,
                day(day),
            )!!,
        )

    // ---- time -------------------------------------------------------------

    /** Day *n* of the test's calendar: 2026-09-01 plus *n*. Day 1 is the bond's first full day. */
    private fun day(n: Int): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n.toLong())

    /** 10:00Z on day *n*: 11:00 in Lagos. */
    private fun at(n: Int): Instant = Instant.parse("${day(n)}T10:00:00Z")

    /** The first instant the job settles day *n*: a minute after it ends. */
    private fun settles(n: Int): Instant = Instant.parse("${day(n)}T23:01:00Z")

    /** Runs the job once, a minute after day *n* ends. Everything up to it is written, closed and evaluated. */
    private fun closeThrough(n: Int) {
        closer.closeElapsedDays(settles(n), BUDGET).failed shouldBe 0
    }

    // ---- two people's requests ------------------------------------------

    private fun bothWriteOn(
        n: Int,
        first: UUID = ada,
        second: UUID = bea,
        into: String = bond,
    ) {
        clock.set(at(n))
        submit(first, into, "hers").status shouldBe 201
        submit(second, into, "his").status shouldBe 201
    }

    private fun writeOn(
        n: Int,
        author: UUID,
    ): String {
        clock.set(at(n))
        return idOf(submit(author, bond, "hers").also { it.status shouldBe 201 })
    }

    private fun writeAt(
        instant: String,
        first: UUID,
        second: UUID,
    ) {
        clock.set(Instant.parse(instant))
        submit(first, bond, "hers").status shouldBe 201
        submit(second, bond, "his").status shouldBe 201
    }

    /** A member's own request, at the clock's instant: the bond records when the setting changed, and the job reads that. */
    private fun strictMode(on: Boolean) {
        val etag =
            mockMvc
                .get("/api/v1/bonds/$bond") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
                .andReturn()
                .response
                .getHeader(HttpHeaders.ETAG)!!
        mockMvc
            .patch("/api/v1/bonds/$bond") {
                header(HttpHeaders.AUTHORIZATION, bearer(ada))
                header(HttpHeaders.IF_MATCH, etag)
                contentType = MediaType.APPLICATION_JSON
                content = """{"strictMode":$on}"""
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun pair(
        creator: UUID,
        joiner: UUID,
        zone: String,
        at: String,
    ): String {
        clock.set(Instant.parse(at))
        val created = createBond(creator, zone)
        accept(joiner, codeOf(created))
        return idOf(created)
    }

    private fun createBond(
        userId: UUID,
        zone: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"$zone"}"""
            }.andReturn()
            .response
            .also { it.status shouldBe 201 }

    private fun accept(
        userId: UUID,
        code: String,
    ) {
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response.status shouldBe 200
    }

    private fun submit(
        caller: UUID,
        bondId: String,
        text: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$text"}"""
            }.andReturn()
            .response

    private fun changeZone(
        proposer: UUID,
        confirmer: UUID,
        zone: String,
    ) {
        mockMvc
            .patch("/api/v1/bonds/$bond/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(proposer))
                contentType = MediaType.APPLICATION_JSON
                content = """{"anchorTimezone":"$zone"}"""
            }.andReturn()
            .response.status shouldBe 200
        val proposalId =
            jdbc.queryForObject(
                "SELECT id FROM bond_proposals WHERE bond_id = ?::uuid AND kind = 'TIMEZONE_CHANGE' ORDER BY proposed_at DESC LIMIT 1",
                UUID::class.java,
                bond,
            )
        mockMvc
            .post("/api/v1/bonds/$bond/timezone/confirm") {
                header(HttpHeaders.AUTHORIZATION, bearer(confirmer))
                contentType = MediaType.APPLICATION_JSON
                content = """{"proposalId":"$proposalId"}"""
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun idOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = Instant.parse("2026-09-01T10:00:00Z"))
    }

    private companion object {
        const val BUDGET = 1_000
    }
}
