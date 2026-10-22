package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.service.RecalculateStreak
import com.moyi.gratitude.service.WithdrawalRig
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `GET /bonds/{bondId}/milestones` (FR-075, doc 06:174; ADR-0041): the four thresholds of
 * complete days a bond has reached, each dated by the day that reached it, and the next.
 *
 * `MilestoneRulesTest` holds the thresholds and `MilestoneReachedTest` the event. This holds
 * what a member is shown: today counted when, and only when, `GET /streak` counts it; the dates
 * read off the bond's days and not the stored streak; one answer for both members and for every
 * way a bond ends.
 *
 * `Africa/Lagos`; day *n* is 2026-09-01 plus *n*, and the bond is paired on day 0. A history too
 * long to write a request at a time is put down by hand ([daysByHand]) as the job would have
 * left it.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class, MarkerReadLastTest.AccessConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class MilestonesEndpointTest(
    @Autowired private val closer: DayCloser,
    @Autowired private val recalculate: RecalculateStreak,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val json: ObjectMapper,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired access: BondAccess,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)
    private val interruptible = access as MarkerReadLastTest.InterruptibleBondAccess
    private val pool = Executors.newCachedThreadPool()

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String

    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(at(0))
        bond = rig.pair(ada, bea)
    }

    /** Before as well as after ([setUp] calls it first): the job and the dispatcher work on every bond in a shared database. */
    @AfterEach
    fun clear() {
        rig.clear()
        users.clear()
    }

    @AfterEach
    fun release() {
        interruptible.disarm()
        pool.shutdownNow()
    }

    @Test
    fun `a bond nobody has written in has reached nothing, and seven is next, for both alike`() {
        for (member in listOf(ada, bea)) body(member) shouldBe NOTHING_YET
    }

    /**
     * Q-M5: reached at the reveal, which on a bond with no reveal time is the second entry. The
     * partner's lone entry moves nothing for either of them (FR-064); once complete, both read
     * the same bytes, again and in either order (H-M1, H-M2). The job then evaluates the day: the
     * date shown is the date kept, and the day the event names.
     */
    @Test
    fun `the seventh complete day is reached as soon as both have written it, and keeps its date when the job evaluates it`() {
        (1..6).forEach { bothWriteOn(it) }
        closeThrough(6)
        clock.set(at(7))
        rig.submit(ada, bond, "thank you")
        listOf(ada, bea).map { body(it) } shouldBe listOf(NOTHING_YET, NOTHING_YET)

        rig.submit(bea, bond, "thank you")

        val reached = reachedSeven(day(7))
        listOf(bea, ada, ada, bea).map { body(it) } shouldBe List(4) { reached }
        streak(ada)["totalCompleteDays"].asInt() shouldBe 7

        closeThrough(7)
        clock.set(at(8))
        body(ada) shouldBe reached
        streak(ada)["totalCompleteDays"].asInt() shouldBe 7
        jdbc.queryForObject(
            """
            SELECT d.date FROM outbox_events o JOIN bond_days d ON d.id = (o.payload->>'bondDayId')::uuid
            WHERE o.event_type = 'MilestoneReached'
            """.trimIndent(),
            LocalDate::class.java,
        ) shouldBe day(7)
    }

    /** As `StreakEndpointTest`'s "today is not added while the day before it is still to be evaluated": the two numbers move together. */
    @Test
    fun `today is not counted while the day before it is still to be evaluated, as the streak does not count it`() {
        (1..6).forEach { bothWriteOn(it) }
        closeThrough(6)
        // Nobody writes on day 7, and the job has not run for it. Both write on day 8.
        bothWriteOn(8)

        body(ada) shouldBe NOTHING_YET
        streak(ada)["totalCompleteDays"].asInt() shouldBe 6

        closeThrough(7)

        body(ada) shouldBe reachedSeven(day(8))
        streak(ada)["totalCompleteDays"].asInt() shouldBe 7
    }

    /** "Both have written" is the reveal: a day waiting for its reveal time is an OPEN square, and counts for neither number yet. */
    @Test
    fun `a day waiting for its reveal time is reached when it reveals, not when the second entry arrives`() {
        (1..6).forEach { bothWriteOn(it) }
        closeThrough(6)
        jdbc.update("UPDATE bonds SET reveal_time_local = '20:00:00'::time WHERE id = ?::uuid", bond) shouldBe 1
        bothWriteOn(7)
        dayStatus(7) shouldBe "PENDING_REVEAL"

        body(ada) shouldBe NOTHING_YET
        streak(ada)["totalCompleteDays"].asInt() shouldBe 6

        // 20:00 in Lagos is 19:00Z. To be confirmed by running: the job reveals a day whose reveal time has come.
        closer.closeElapsedDays(Instant.parse("${day(7)}T19:01:00Z"), BUDGET).failed shouldBe 0
        dayStatus(7) shouldBe "REVEALED"

        body(ada) shouldBe reachedSeven(day(7))
        streak(ada)["totalCompleteDays"].asInt() shouldBe 7
    }

    @Test
    fun `all four, and nothing after the last`() {
        daysByHand(bond, through = 364)
        clock.set(at(365))
        rig.submit(ada, bond, "thank you")
        val three =
            """{"threshold":7,"achievedOn":"${day(7)}"},{"threshold":30,"achievedOn":"${day(30)}"},""" +
                """{"threshold":100,"achievedOn":"${day(100)}"}"""
        body(bea) shouldBe """{"achieved":[$three],"next":{"threshold":365}}"""

        rig.submit(bea, bond, "thank you")

        body(ada) shouldBe """{"achieved":[$three,{"threshold":365,"achievedOn":"${day(365)}"}],"next":null}"""
    }

    /**
     * H-M4 and the controller's default 1: `takesWrites` (ADR-0041 decision 7). Four bonds with
     * the same history on the same dates, ended four ways: a leave, a block with withdrawal, a
     * block without it (review m2), a deletion countdown. Every member of every one reads the same body and the same headers,
     * before the job and the erasure run and after. A countdown called off gives `next` back.
     */
    @Test
    fun `an ended bond keeps what it reached with nothing next, the same bytes left, blocked or counting down, for both members`() {
        val cy = users.verified("Cy")
        val dee = users.verified("Dee")
        val fay = users.verified("Fay")
        val gus = users.verified("Gus")
        val hal = users.verified("Hal")
        val ivy = users.verified("Ivy")
        val left = bond
        val blocked = rig.pair(cy, dee)
        val counting = rig.pair(fay, gus)
        val plainlyBlocked = rig.pair(hal, ivy)
        val bonds =
            listOf(
                left to listOf(ada, bea),
                blocked to listOf(cy, dee),
                counting to listOf(fay, gus),
                plainlyBlocked to listOf(hal, ivy),
            )
        bonds.forEach { (b, _) -> daysByHand(b, through = 6) }
        clock.set(at(7))
        bonds.forEach { (b, members) -> members.forEach { rig.submit(it, b, "thank you") } }
        // Not vacuous: each has a next while it takes writes.
        bonds.forEach { (b, members) -> body(members.first(), b) shouldBe reachedSeven(day(7)) }

        clock.set(at(7).plusSeconds(3_600))
        rig.leave(bea, left)
        rig.block(dee, blocked, true)
        rig.block(ivy, plainlyBlocked, false)
        deletionRequest(fay, counting, "POST")
        deletionRequest(gus, counting, "POST")
        // To be confirmed by running, as in OnThisDayTest: two requests put the bond in PENDING_DELETION.
        jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?::uuid", String::class.java, counting) shouldBe "PENDING_DELETION"

        val ended = """{"achieved":[{"threshold":7,"achievedOn":"${day(7)}"}],"next":null}"""
        val answers = bonds.flatMap { (b, members) -> members.map { raw(it, b) } }
        answers.map { it.status to it.contentAsString }.toSet() shouldBe setOf(200 to ended)
        val headers = answers.map(::headersOf).toSet()
        withClue("$headers") { headers.size shouldBe 1 }

        // The withdrawal's erasure runs, and the job evaluates day 7 on all four: the record is the same.
        dispatcher.dispatchDue(clock.instant(), DISPATCH_BUDGET).failed shouldBe 0
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND author_member_id = ? AND text IS NOT NULL",
            Int::class.java,
            blocked,
            rig.memberId(blocked, dee),
        ) shouldBe 0
        closeThrough(7)
        clock.set(at(9))
        bonds.flatMap { (b, members) -> members.map { raw(it, b) } }.map { it.contentAsString }.toSet() shouldBe setOf(ended)
        // Decision 9: a day both wrote on before the end is announced, ended or not.
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MilestoneReached'", Int::class.java) shouldBe 4

        deletionRequest(fay, counting, "DELETE")
        body(gus, counting) shouldBe reachedSeven(day(7))
    }

    @Test
    fun `a bond still waiting for its second person has reached nothing, whatever its creator wrote`() {
        val cara = users.verified("Cara")
        clock.set(at(1))
        val waiting = rig.idOf(rig.create(cara))
        rig.submit(cara, waiting, "thank you")
        closeThrough(1)
        clock.set(at(2))
        rig.submit(cara, waiting, "thank you")

        body(cara, waiting) shouldBe NOTHING_YET
    }

    /** Decision 2: the dates are the timeline's, which `recalculate` trusts (FR-074). A drifted row moves the streak, not a date. */
    @Test
    fun `the dates are the bond's days, not the stored streak - a drifted row moves nothing, and a recalculation announces nothing`() {
        (1..7).forEach { bothWriteOn(it) }
        closeThrough(7)
        clock.set(at(8))
        val reached = body(ada)
        reached shouldBe reachedSeven(day(7))

        jdbc.update("UPDATE streak_states SET total_complete_days = 2 WHERE bond_id = ?::uuid", bond) shouldBe 1
        // Not vacuous: the streak's own number has drifted.
        streak(ada)["totalCompleteDays"].asInt() shouldBe 2
        body(ada) shouldBe reached

        recalculate.recalculate(UUID.fromString(bond)).changed shouldBe true
        streak(ada)["totalCompleteDays"].asInt() shouldBe 7
        body(ada) shouldBe reached
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MilestoneReached'", Int::class.java) shouldBe 1
    }

    /** FR-076, H-M2, H-M3, Q-M3: thresholds and dates about the bond, and no count to make a countdown of. Named here, all of them. */
    @Test
    fun `the payload carries these fields and no other, and a stranger gets the one 404`() {
        (1..6).forEach { bothWriteOn(it) }
        closeThrough(6)
        bothWriteOn(7)

        val payload = json.readTree(body(ada))

        payload.propertyNames().toSet() shouldBe setOf("achieved", "next")
        payload["achieved"].single().propertyNames().toSet() shouldBe setOf("threshold", "achievedOn")
        payload["next"].propertyNames().toSet() shouldBe setOf("threshold")
        val eve = users.verified("Eve")
        val refused = raw(eve, bond)
        refused.status shouldBe 404
        refused.contentAsString.replace("/milestones", "/streak") shouldBe get(eve, "/api/v1/bonds/$bond/streak").contentAsString
        raw(ada, "not-an-id").status shouldBe 404
    }

    /**
     * The controller's ruling 1 (2026-10-22): `next` is asked of the membership resolved again
     * after the joining-day reconcile, not of the guard's, as `GET /on-this-day` asks it (C6b
     * final review m2). An ending can commit while the reconcile waits on the bond's lock.
     */
    @Test
    fun `a block committed after the guard resolved the membership leaves nothing next`() {
        // Not vacuous: the bond takes writes, and seven is next.
        body(bea) shouldBe NOTHING_YET
        // The first resolution is the guard's. A block without withdrawal commits after it: the guard's membership
        // still takes writes.
        interruptible.afterResolution(1) {
            pool.submit { rig.block(ada, bond, false) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        val raced = raw(bea, bond)

        interruptible.fired shouldBe true
        raced.status shouldBe 200
        raced.contentAsString shouldBe NOTHING_NEXT
        body(bea) shouldBe NOTHING_NEXT
    }

    // ---- helpers ----------------------------------------------------------

    private fun reachedSeven(on: LocalDate): String = """{"achieved":[{"threshold":7,"achievedOn":"$on"}],"next":{"threshold":30}}"""

    private fun body(
        member: UUID,
        b: String = bond,
    ): String = raw(member, b).also { it.status shouldBe 200 }.contentAsString

    private fun raw(
        member: UUID,
        b: String,
    ): MockHttpServletResponse = get(member, "/api/v1/bonds/$b/milestones")

    private fun streak(member: UUID): JsonNode =
        json.readTree(get(member, "/api/v1/bonds/$bond/streak").also { it.status shouldBe 200 }.contentAsString)

    private fun get(
        member: UUID,
        path: String,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(member).token}") }
            .andReturn()
            .response

    /** Every header of [response] by name, but those that change from one moment to the next. */
    private fun headersOf(response: MockHttpServletResponse): Map<String, List<String>> =
        response.headerNames
            .filterNot { name -> VOLATILE_HEADERS.any { it.equals(name, ignoreCase = true) } }
            .sorted()
            .associateWith { response.getHeaders(it) }

    private fun deletionRequest(
        user: UUID,
        b: String,
        method: String,
    ) {
        val path = "/api/v1/bonds/$b/deletion-request"
        val bearer = "Bearer ${tokens.issue(user).token}"
        val response =
            if (method == "POST") {
                mockMvc.post(path) { header(HttpHeaders.AUTHORIZATION, bearer) }.andReturn().response
            } else {
                mockMvc.delete(path) { header(HttpHeaders.AUTHORIZATION, bearer) }.andReturn().response
            }
        withClue("$method deletion-request: ${response.contentAsString}") { (response.status / 100) shouldBe 2 }
    }

    private fun dayStatus(n: Int): String? =
        jdbc.queryForObject("SELECT status FROM bond_days WHERE bond_id = ?::uuid AND date = ?", String::class.java, bond, day(n))

    /**
     * Day 0 (the pairing day: nobody wrote) and days 1 to [through], both written, closed and
     * evaluated, as the job would have left them, with the streak row that fold gives outside
     * Strict mode: a freeze banked every fourteenth day, two at most (BR-5). No entries: nothing
     * here reads them. The series is of `timestamp`, so `AT TIME ZONE 'UTC'` makes the instants
     * whatever the session's zone. To be confirmed by running: pairing leaves no row for day 0,
     * so the insert makes `through + 1` rows.
     */
    private fun daysByHand(
        b: String,
        through: Int,
    ) {
        jdbc.update(
            """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at,
                                   closed_at, version, evaluated_at, evaluated_as, evaluated_strict, freeze_applied)
            SELECT gen_random_uuid(), ?::uuid, d::date, CASE WHEN d::date = ?::date THEN 'EMPTY' ELSE 'REVEALED' END, 'Africa/Lagos',
                   (d AT TIME ZONE 'UTC') - interval '1 hour', (d AT TIME ZONE 'UTC') + interval '23 hours',
                   CASE WHEN d::date = ?::date THEN 0 ELSE 2 END, (d AT TIME ZONE 'UTC') - interval '1 hour',
                   (d AT TIME ZONE 'UTC') + interval '23 hours 1 minute', 0, (d AT TIME ZONE 'UTC') + interval '23 hours 1 minute',
                   CASE WHEN d::date = ?::date THEN 'MISSED' ELSE 'COMPLETE' END, false, false
            FROM generate_series(?::date::timestamp, ?::date::timestamp, interval '1 day') AS d
            """.trimIndent(),
            b,
            day(0),
            day(0),
            day(0),
            day(0),
            day(through),
        ) shouldBe through + 1
        jdbc.update(
            """
            INSERT INTO streak_states (bond_id, current_streak, longest_streak, last_complete_date, freezes_available,
                                       freeze_progress, freezes_consumed, total_complete_days, updated_at)
            VALUES (?::uuid, ?, ?, ?, ?, ?, 0, ?, ?)
            """.trimIndent(),
            b,
            through,
            through,
            day(through),
            minOf(2, through / 14),
            through % 14,
            through,
            Timestamp.from(clock.instant()),
        ) shouldBe 1
    }

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n.toLong())

    private fun at(n: Int): Instant = Instant.parse("${day(n)}T10:00:00Z")

    private fun closeThrough(n: Int) {
        closer.closeElapsedDays(Instant.parse("${day(n)}T23:01:00Z"), BUDGET).failed shouldBe 0
    }

    private fun bothWriteOn(n: Int) {
        clock.set(at(n))
        rig.submit(ada, bond, "thank you")
        rig.submit(bea, bond, "thank you")
    }

    private companion object {
        const val NOTHING_YET = """{"achieved":[],"next":{"threshold":7}}"""
        const val NOTHING_NEXT = """{"achieved":[],"next":null}"""
        const val TIMEOUT_SECONDS = 10L
        const val BUDGET = 1_000
        const val DISPATCH_BUDGET = 10
        val VOLATILE_HEADERS = listOf(HttpHeaders.DATE, HttpHeaders.LAST_MODIFIED)
    }
}
