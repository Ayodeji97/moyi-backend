package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.ArchiveExports
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeAccountStanding
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.AccountStanding
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Admin A3, spec §4 (rulings 16 and 17): what a suspension does to the days of a bond it pauses.
 * A day any part of which overlaps a span closes `SUSPENDED` when nobody, or one, wrote on it; a
 * reveal that falls due inside a span is withheld; a missing day in one is written `SUSPENDED`.
 * Every day is made by the real routes and the real close job; spans through [FakeAccountStanding];
 * time through [MutableClock].
 *
 * `Africa/Lagos` unless a test says otherwise: day *n* is 2026-09-01 plus *n*, running
 * `[day(n-1)T23:00Z, day(n)T23:00Z)`. `ada` is the suspended member; `bea` is the partner.
 *
 * Each test's comment names the change that makes it fail.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SuspendedDaysTest.TimeConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // A test's collaborators, each named; one rule, many days.
internal class SuspendedDaysTest(
    @Autowired private val closer: DayCloser,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val exports: ArchiveExports,
    @Autowired directory: UserDirectory,
    @Autowired standing: AccountStanding,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val suspensions = standing as FakeAccountStanding
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
    }

    /** Before as well as after: the job works on every bond in a shared database. */
    @AfterEach
    fun clear() {
        suspensions.reset()
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute(
            "TRUNCATE TABLE idempotency_keys, entries, bond_days, bond_anchor_intervals, bond_proposals, " +
                "blocks, bond_invites, bond_members, bonds CASCADE",
        )
        users.clear()
    }

    // ---- a day nobody, or one, wrote on --------------------------------------

    @Test
    fun `an OPEN day a span overlaps closes SUSPENDED, and nothing announces it`() {
        // `paused` dropped from CloseDay's close: the day closes EMPTY with a DayClosed.
        val bond = pairedBond()
        val hers = idOf(writeAt(bond, bea, at(4), "a word withdrawn"))
        deleteEntry(bea, hers).status shouldBe 204
        statusOf(bond, day(4)) shouldBe "OPEN"
        suspensions.suspend(ada, at(4).plusSeconds(3_600))

        closeThrough(4)

        statusOf(bond, day(4)) shouldBe "SUSPENDED"
        closedAtOf(bond, day(4)) shouldBe closeRun(4)
        dayClosedFor(bond, day(4)) shouldBe 0
    }

    /**
     * Review Focus 3, anti-goal 4: the suspended member's lone entry never reaches the partner.
     * Not revealed at the close, and not shown by any read the partner has, before or after the lift.
     */
    @Test
    fun `the suspended member's lone entry closes SUSPENDED and no read shows the partner a word of it`() {
        // A paused PARTIAL closing SOLO: FR-063 unlocks the entry and every read below shows it.
        val bond = pairedBond()
        writeAt(bond, ada, at(4), "marmalade sunrise")
        suspensions.suspend(ada, at(4).plusSeconds(3_600))
        clock.set(at(4).plusSeconds(7_200))
        everyReadOf(bea, bond, day(4)).forEach { (read, body) -> withClue(read) { body shouldNotContain WORD } }

        closeThrough(4)

        statusOf(bond, day(4)) shouldBe "SUSPENDED"
        revealedEntries(bond) shouldBe 0
        dayClosedFor(bond, day(4)) shouldBe 0
        everyReadOf(bea, bond, day(4)).forEach { (read, body) -> withClue(read) { body shouldNotContain WORD } }

        suspensions.lift(ada, at(6))
        writeAt(bond, bea, at(6), "after the lift")
        closeThrough(6)

        statusOf(bond, day(4)) shouldBe "SUSPENDED"
        revealedEntries(bond) shouldBe 0
        everyReadOf(bea, bond, day(4)).forEach { (read, body) -> withClue(read) { body shouldNotContain WORD } }
        // The words are there to be found: their author's own archive has them.
        dayAsRead(ada, bond, day(4)).contentAsString shouldContain WORD
    }

    @Test
    fun `the partner's lone entry closes SUSPENDED, not SOLO, and nothing is unlocked`() {
        // A paused PARTIAL closing SOLO: the day reads SOLO and the entry is stamped revealed.
        val bond = pairedBond()
        writeAt(bond, bea, at(4), "hers alone")
        suspensions.suspend(ada, at(4).plusSeconds(3_600))

        closeThrough(4)

        statusOf(bond, day(4)) shouldBe "SUSPENDED"
        revealedEntries(bond) shouldBe 0
        dayClosedFor(bond, day(4)) shouldBe 0
        evaluatedAs(bond, day(4)) shouldBe "SUSPENDED"
    }

    @Test
    fun `a day both wrote and read together before the pause stays REVEALED, and is complete`() {
        val bond = pairedBond()
        writeAt(bond, ada, at(4), "his")
        writeAt(bond, bea, at(4), "hers")
        statusOf(bond, day(4)) shouldBe "REVEALED"
        suspensions.suspend(ada, at(4).plusSeconds(3_600))

        closeThrough(4)

        statusOf(bond, day(4)) shouldBe "REVEALED"
        evaluatedAs(bond, day(4)) shouldBe "COMPLETE"
        revealedEntries(bond) shouldBe 2
    }

    // ---- ruling 17: a reveal that falls due inside a span ------------------------

    @Test
    fun `a reveal due inside a span is withheld, even by a run after the lift, and the day is complete`() {
        // The suspendedAt term dropped from isWithheldBy: the 21:00 run reveals the day.
        val bond = pairedBond()
        revealAt(bond, "20:00") // 19:00Z
        writeAt(bond, ada, at(4), "quince orchard")
        writeAt(bond, bea, at(4), "hers")
        statusOf(bond, day(4)) shouldBe "PENDING_REVEAL"
        suspensions.suspend(ada, Instant.parse("${day(4)}T12:00:00Z"))
        suspensions.lift(ada, Instant.parse("${day(4)}T20:00:00Z"))

        // After the lift, before the day's end: its reveal fell due inside the span.
        clock.set(Instant.parse("${day(4)}T21:00:00Z"))
        closer.closeElapsedDays(Instant.parse("${day(4)}T21:00:00Z"), BUDGET).failed shouldBe 0
        statusOf(bond, day(4)) shouldBe "PENDING_REVEAL"
        revealedEntries(bond) shouldBe 0

        closeThrough(4)

        statusOf(bond, day(4)) shouldBe "PENDING_REVEAL"
        closedAtOf(bond, day(4)) shouldBe closeRun(4)
        revealedEntries(bond) shouldBe 0
        jdbc.queryForObject(
            "SELECT revealed_at FROM bond_days WHERE bond_id = ?::uuid AND date = ?",
            Timestamp::class.java,
            bond,
            day(4),
        ) shouldBe null
        outbox("DayRevealed") shouldBe 0
        evaluatedAs(bond, day(4)) shouldBe "COMPLETE"
        everyReadOf(bea, bond, day(4)).forEach { (read, body) -> withClue(read) { body shouldNotContain "quince" } }
    }

    @Test
    fun `a reveal due one microsecond before the span is revealed as normal`() {
        val bond = pairedBond()
        revealAt(bond, "20:00")
        writeAt(bond, ada, at(4), "his")
        writeAt(bond, bea, at(4), "hers")
        suspensions.suspend(ada, Instant.parse("${day(4)}T19:00:00.000001Z"))

        closeThrough(4)

        statusOf(bond, day(4)) shouldBe "REVEALED"
        revealedEntries(bond) shouldBe 2
        outbox("DayRevealed") shouldBe 1
    }

    @Test
    fun `a reveal due at the very instant the span begins is withheld`() {
        val bond = pairedBond()
        revealAt(bond, "20:00")
        writeAt(bond, ada, at(4), "his")
        writeAt(bond, bea, at(4), "hers")
        suspensions.suspend(ada, Instant.parse("${day(4)}T19:00:00Z"))

        closeThrough(4)

        statusOf(bond, day(4)) shouldBe "PENDING_REVEAL"
        revealedEntries(bond) shouldBe 0
    }

    // ---- missing days, the lift day, and the day after ---------------------------

    @Test
    fun `days nobody opened inside a span are written SUSPENDED, and nothing announces them`() {
        // `wasSuspendedDuring` dropped from CreateMissingDays: the three are EMPTY, each with a DayClosed.
        val bond = pairedBond()
        writeAt(bond, ada, at(3), "his")
        writeAt(bond, bea, at(3), "hers")
        suspensions.suspend(ada, at(4))
        suspensions.lift(ada, at(7))

        closeThrough(7)

        (4..7).map { statusOf(bond, day(it)) } shouldBe List(4) { "SUSPENDED" }
        (4..7).sumOf { dayClosedFor(bond, day(it)) } shouldBe 0
        (4..7).map { evaluatedAs(bond, day(it)) } shouldBe List(4) { "SUSPENDED" }
    }

    @Test
    fun `the lift day with one entry is SUSPENDED, and the day after it with one entry is SOLO`() {
        val bond = pairedBond()
        suspensions.suspend(ada, at(4))
        suspensions.lift(ada, at(5))
        writeAt(bond, bea, at(5).plusSeconds(60), "the lift day")
        writeAt(bond, bea, at(6), "the day after")

        closeThrough(6)

        statusOf(bond, day(5)) shouldBe "SUSPENDED"
        statusOf(bond, day(6)) shouldBe "SOLO"
        evaluatedAs(bond, day(5)) shouldBe "SUSPENDED"
        evaluatedAs(bond, day(6)) shouldBe "MISSED"
    }

    /**
     * Review Focus 4. Kiritimati to Pago Pago, confirmed on Kiritimati's 16th (as `CloseMatrixTest`):
     * the row was opened ending 09-16T10:00Z, and the day now runs to 09-17T11:00Z. A span that
     * begins at 12:00Z on the 16th is inside the day's extra stretch, after its stored end.
     */
    @Test
    fun `a day a westward zone change lengthened is SUSPENDED by a span that begins in its extra hours`() {
        // The stored row's bounds passed to wasSuspendedDuring instead of `current`'s: the day closes SOLO.
        val bond = pairedBond("Pacific/Kiritimati", Instant.parse("2026-09-12T12:00:00Z"))
        writeAt(bond, ada, Instant.parse("2026-09-15T12:00:00Z"), "before the move")
        clock.set(Instant.parse("2026-09-15T20:00:00Z"))
        changeZone(bond, "Pacific/Pago_Pago")
        endsAtOf(bond, LocalDate.parse("2026-09-16")) shouldBe Instant.parse("2026-09-16T10:00:00Z")
        suspensions.suspend(ada, Instant.parse("2026-09-16T12:00:00Z"))
        suspensions.lift(ada, Instant.parse("2026-09-16T13:00:00Z"))

        closer.closeElapsedDays(Instant.parse("2026-09-17T23:02:00Z"), BUDGET).failed shouldBe 0

        endsAtOf(bond, LocalDate.parse("2026-09-16")) shouldBe Instant.parse("2026-09-17T11:00:00Z")
        statusOf(bond, LocalDate.parse("2026-09-16")) shouldBe "SUSPENDED"
        revealedEntries(bond) shouldBe 0
    }

    /**
     * Spec §4's last paragraph: the decision is stable from the day's end. Two bonds, the same day,
     * each with one entry by the partner; one member's span still open when the job closes it, the
     * other's lifted after the day ended and before the job came.
     */
    @Test
    fun `a day closed while its span is open and its twin closed after the span was lifted are the same`() {
        val open = pairedBond()
        val cy = users.verified("Cy")
        val dee = users.verified("Dee")
        val lifted = pairedBond(creator = cy, joiner = dee)
        writeAt(open, bea, at(4), "one")
        writeAt(lifted, dee, at(4), "two")
        suspensions.suspend(ada, at(4).plusSeconds(3_600))
        suspensions.suspend(cy, at(4).plusSeconds(3_600))
        // After day 4 ended (23:00Z), before the job settles it.
        suspensions.lift(cy, Instant.parse("${day(5)}T01:00:00Z"))

        closeThrough(4)

        statusOf(open, day(4)) shouldBe "SUSPENDED"
        statusOf(lifted, day(4)) shouldBe statusOf(open, day(4))
        evaluatedAs(lifted, day(4)) shouldBe evaluatedAs(open, day(4))
    }

    // ---- ReconcileJoiningDay ----------------------------------------------------

    /**
     * A legacy joining day (`JoiningDayTest.legacyJoiningDay`: what C1 committed, `SUSPENDED` with both
     * entries, built by hand because no request can produce it any more), in a bond paused since.
     * The first read meets it; it must not be the way the suspended member's words reach the partner.
     */
    @Test
    fun `a paused bond's joining day both wrote on is not revealed by a read`() {
        // `membership.paused` dropped from ReconcileJoiningDay: the partner's read reveals both entries.
        clock.set(at(4))
        val created = createBond(ada)
        val bond = idOf(created)
        writeAt(bond, ada, at(4), "marmalade sunrise")
        accept(bea, codeOf(created))
        writeAt(bond, bea, at(4).plusSeconds(60), "partner joining words")
        jdbc.update("UPDATE bond_days SET status = 'SUSPENDED', revealed_at = NULL WHERE bond_id = ?::uuid", bond)
        jdbc.update("UPDATE entries SET status = 'SUBMITTED', revealed_at = NULL WHERE bond_id = ?::uuid", bond)
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        suspensions.suspend(ada, at(4).plusSeconds(3_600))
        clock.set(at(4).plusSeconds(7_200))

        get(bea, "/api/v1/bonds/$bond/today").let {
            it.status shouldBe 200
            it.contentAsString shouldNotContain WORD
        }

        statusOf(bond, day(4)) shouldBe "PENDING_REVEAL"
        revealedEntries(bond) shouldBe 0
        outbox("DayRevealed") shouldBe 0
    }

    // ---- helpers ----------------------------------------------------------

    /** Every read the partner has of [date] (Review Focus 3), as `(name, body)`. */
    private fun everyReadOf(
        reader: UUID,
        bond: String,
        date: LocalDate,
    ): List<Pair<String, String>> {
        val reads =
            listOf(
                "GET /days/{date}" to dayAsRead(reader, bond, date),
                "GET /today" to get(reader, "/api/v1/bonds/$bond/today"),
                "GET /days" to get(reader, "/api/v1/bonds/$bond/days"),
                "GET /search" to get(reader, "/api/v1/bonds/$bond/search?q=$WORD"),
            )
        reads.forEach { (read, response) -> withClue(read) { (response.status in setOf(200, 404)) shouldBe true } }
        val export = exportOf(reader, bond)
        return reads.map { (read, response) -> read to response.contentAsString } + ("export" to export)
    }

    private fun exportOf(
        reader: UUID,
        bond: String,
    ): String {
        val lines = mutableListOf<String>()
        var before: LocalDate? = null
        do {
            val page = exports.page(reader, UUID.fromString(bond), before)
            page.days.forEach { day -> day.entries.forEach { lines += "${day.date} ${day.status} ${it.status} ${it.text}" } }
            before = page.next
        } while (before != null)
        return lines.joinToString("\n")
    }

    private fun dayAsRead(
        reader: UUID,
        bond: String,
        date: LocalDate,
    ): MockHttpServletResponse = get(reader, "/api/v1/bonds/$bond/days/$date")

    private fun get(
        reader: UUID,
        path: String,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) { header(HttpHeaders.AUTHORIZATION, bearer(reader)) }
            .andReturn()
            .response

    private fun <T> withClue(
        clue: String,
        block: () -> T,
    ): T = io.kotest.assertions.withClue(clue, block)

    private fun statusOf(
        bond: String,
        date: LocalDate,
    ): String? =
        jdbc
            .query("SELECT status FROM bond_days WHERE bond_id = ?::uuid AND date = ?", { row, _ -> row.getString(1) }, bond, date)
            .singleOrNull()

    private fun evaluatedAs(
        bond: String,
        date: LocalDate,
    ): String? =
        jdbc
            .query("SELECT evaluated_as FROM bond_days WHERE bond_id = ?::uuid AND date = ?", { row, _ -> row.getString(1) }, bond, date)
            .singleOrNull()

    private fun closedAtOf(
        bond: String,
        date: LocalDate,
    ): Instant? =
        jdbc
            .queryForObject("SELECT closed_at FROM bond_days WHERE bond_id = ?::uuid AND date = ?", Timestamp::class.java, bond, date)
            ?.toInstant()

    private fun endsAtOf(
        bond: String,
        date: LocalDate,
    ): Instant? =
        jdbc
            .queryForObject("SELECT ends_at FROM bond_days WHERE bond_id = ?::uuid AND date = ?", Timestamp::class.java, bond, date)
            ?.toInstant()

    private fun revealedEntries(bond: String): Int? =
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND revealed_at IS NOT NULL", Int::class.java, bond)

    private fun dayClosedFor(
        bond: String,
        date: LocalDate,
    ): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events o JOIN bond_days d ON d.id = o.aggregate_id " +
                "WHERE o.event_type = 'DayClosed' AND d.bond_id = ?::uuid AND d.date = ?",
            Int::class.java,
            bond,
            date,
        ) ?: 0

    private fun outbox(type: String): Int? =
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Int::class.java, type)

    private fun revealAt(
        bond: String,
        time: String,
    ) {
        // A setting, as every reveal test sets it (RevealTest).
        jdbc.update("UPDATE bonds SET reveal_time_local = ?::time WHERE id = ?::uuid", time, bond) shouldBe 1
    }

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n.toLong())

    private fun at(n: Int): Instant = Instant.parse("${day(n)}T10:00:00Z")

    /** When [closeThrough] runs for day [n]: its end (23:00Z), the 12-hour grace, and the minute after. */
    private fun closeRun(n: Int): Instant = Instant.parse("${day(n + 1)}T11:01:00Z")

    private fun closeThrough(n: Int) {
        closer.closeElapsedDays(closeRun(n), BUDGET).failed shouldBe 0
    }

    private fun pairedBond(
        zone: String = "Africa/Lagos",
        createdAt: Instant = at(0),
        creator: UUID = ada,
        joiner: UUID = bea,
    ): String {
        clock.set(createdAt)
        val created = createBond(creator, zone)
        accept(joiner, codeOf(created))
        return idOf(created)
    }

    private fun createBond(
        creator: UUID,
        zone: String = "Africa/Lagos",
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(creator))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"$zone"}"""
            }.andReturn()
            .response
            .also { it.status shouldBe 201 }

    private fun accept(
        joiner: UUID,
        code: String,
    ) {
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(joiner)) }
            .andReturn()
            .response.status shouldBe 200
    }

    private fun writeAt(
        bond: String,
        author: UUID,
        at: Instant,
        text: String,
    ): MockHttpServletResponse {
        clock.set(at)
        return mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(author))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$text"}"""
            }.andReturn()
            .response
            .also { it.status shouldBe 201 }
    }

    private fun deleteEntry(
        author: UUID,
        id: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/entries/$id") { header(HttpHeaders.AUTHORIZATION, bearer(author)) }
            .andReturn()
            .response

    /** Two-party consent (ADR-0030), as `CloseMatrixTest`: Ada proposes, Bea confirms, at the clock's instant. */
    private fun changeZone(
        bond: String,
        zone: String,
    ) {
        mockMvc
            .patch("/api/v1/bonds/$bond/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(ada))
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
                header(HttpHeaders.AUTHORIZATION, bearer(bea))
                contentType = MediaType.APPLICATION_JSON
                content = """{"proposalId":"$proposalId"}"""
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun idOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = Instant.parse("2026-09-01T10:00:00Z"))
    }

    private companion object {
        const val WORD = "marmalade"
        const val BUDGET = 1_000
    }
}
