package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * `GET /bonds/{bondId}/streak` and the `streak` object on `GET /today` (spec
 * §5.1, §5.2): what two members are shown of the run the close job keeps.
 *
 * `StreakEvaluationTest` holds what the job stores. This holds the one thing
 * a read adds — today, when today is complete — and what the payload may and
 * may not say.
 *
 * `Africa/Lagos`; day *n* is 2026-09-01 plus *n*, and the bond is paired on
 * day 0.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(StreakEndpointTest.TimeConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class StreakEndpointTest(
    @Autowired private val closer: DayCloser,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val json: ObjectMapper,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String

    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(at(0))
        val created = createBond(ada)
        bond = idOf(created)
        accept(bea, codeOf(created))
    }

    /** Before as well as after ([setUp] calls it first): the job these tests run works on every bond in a shared database. */
    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a bond nobody has written in has a streak of nothing, and an empty calendar`() {
        val streak = streak(ada)

        streak.numbers() shouldBe Numbers(current = 0, longest = 0, freezes = 0, progress = 0, total = 0)
        streak["strictMode"].asBoolean() shouldBe false
        streak["lastCompleteDate"].isNull shouldBe true
        streak["days"].size() shouldBe 0
        today(ada)["streak"].toString() shouldBe """{"current":0,"longest":0,"freezesAvailable":0,"strictMode":false}"""
    }

    // --- today counts when it is complete, and only then (FR-071) ---

    @Test
    fun `a couple at three who have both written today are shown four today, and still four after midnight`() {
        (1..3).forEach { bothWriteOn(it) }
        closeThrough(3)
        streak(ada)["current"].asInt() shouldBe 3

        bothWriteOn(4)

        for (member in listOf(ada, bea)) {
            streak(member).numbers() shouldBe Numbers(current = 4, longest = 4, freezes = 0, progress = 4, total = 4)
            streak(member)["lastCompleteDate"].asString() shouldBe day(4).toString()
            today(member)["streak"]["current"].asInt() shouldBe 4
        }

        // The job evaluates day 4. It is in the stored run now, and must not be added a second time.
        closeThrough(4)
        clock.set(at(5))
        streak(ada).numbers() shouldBe Numbers(current = 4, longest = 4, freezes = 0, progress = 4, total = 4)
    }

    @Test
    fun `the first day a couple both write on is a streak of one, before any day has been evaluated`() {
        clock.set(at(0).plusSeconds(600))
        submit(ada)
        submit(bea)

        streak(bea)["current"].asInt() shouldBe 1
        today(bea)["streak"]["longest"].asInt() shouldBe 1
    }

    @Test
    fun `a day only one has written on adds nothing`() {
        (1..3).forEach { bothWriteOn(it) }
        closeThrough(3)
        clock.set(at(4))
        submit(ada)

        streak(bea)["current"].asInt() shouldBe 3
        today(bea)["streak"]["current"].asInt() shouldBe 3
    }

    /**
     * The job is a run behind: yesterday is over and not evaluated. Today is
     * not added to a run that yesterday may have ended; the stored run stands
     * until the job has caught up, and then says what happened.
     */
    @Test
    fun `today is not added while the day before it is still to be evaluated`() {
        (1..3).forEach { bothWriteOn(it) }
        closeThrough(3)
        // Nobody writes on day 4, and the job has not run for it. Both write on day 5.
        bothWriteOn(5)

        streak(ada)["current"].asInt() shouldBe 3

        closeThrough(4)
        streak(ada).numbers() shouldBe Numbers(current = 1, longest = 3, freezes = 0, progress = 4, total = 4)
    }

    // --- the calendar ---

    /**
     * `states.md` §7: "Solo is not rendered on the calendar ... you know your
     * own history, so every Solo cell resolves to a partner miss." Day 0
     * nobody wrote on; day 2 only Ada did; today only Bea has. The calendar
     * says the same of the first two, and of today only that it is open.
     */
    @Test
    fun `the calendar is a square a day, oldest first, and never says that only one wrote`() {
        bothWriteOn(1)
        clock.set(at(2))
        submit(ada)
        closeThrough(2)
        clock.set(at(3))
        submit(bea)

        for (member in listOf(ada, bea)) {
            calendar(member) shouldBe listOf("${day(0)} MISSED", "${day(1)} COMPLETE", "${day(2)} MISSED", "${day(3)} OPEN")
        }
        submit(ada)
        calendar(bea).last() shouldBe "${day(3)} COMPLETE"
    }

    @Test
    fun `a day a freeze covered is a rest day on the calendar, whoever wrote on it`() {
        (1..14).forEach { bothWriteOn(it) }
        clock.set(at(15))
        submit(ada)
        closeThrough(16)
        clock.set(at(17))

        // Day 15, one entry, covered by the freeze; day 16, none, and nothing left to cover it.
        calendar(bea).takeLast(3) shouldBe listOf("${day(14)} COMPLETE", "${day(15)} FROZEN", "${day(16)} MISSED")
    }

    /** FR-072, as the couple sees it: the fourteenth day shows the freeze it earned beside the fourteen, not at midnight. */
    @Test
    fun `on the fourteenth day the freeze shows as earned as soon as both have written`() {
        (1..13).forEach { bothWriteOn(it) }
        closeThrough(13)

        bothWriteOn(14)

        streak(ada).numbers() shouldBe Numbers(current = 14, longest = 14, freezes = 1, progress = 0, total = 14)
        today(bea)["streak"]["freezesAvailable"].asInt() shouldBe 1
        closeThrough(14)
        clock.set(at(15))
        streak(ada).numbers() shouldBe Numbers(current = 14, longest = 14, freezes = 1, progress = 0, total = 14)
    }

    /**
     * Doc 04 §8.3: an ended bond's record is kept. The day both wrote on
     * before one of them left stays in it, the calendar stops where the bond
     * did, and a year on it has not scrolled away.
     */
    @Test
    fun `an ended bond keeps the streak it was shown, and a calendar that ends where it did`() {
        (1..2).forEach { bothWriteOn(it) }
        closeThrough(2)
        bothWriteOn(3)
        streak(ada)["current"].asInt() shouldBe 3
        clock.set(at(3).plusSeconds(3_600))
        leave(bea)
        streak(ada)["current"].asInt() shouldBe 3

        closeThrough(5)
        clock.set(at(500))

        for (member in listOf(ada, bea)) {
            streak(member).numbers() shouldBe Numbers(current = 3, longest = 3, freezes = 0, progress = 3, total = 3)
            calendar(member) shouldBe listOf("${day(0)} MISSED", "${day(1)} COMPLETE", "${day(2)} COMPLETE", "${day(3)} COMPLETE")
        }
    }

    @Test
    fun `a bond still waiting for its second person has no streak and no calendar, whatever its creator wrote`() {
        clear()
        val cara = users.verified("Cara")
        clock.set(at(1))
        bond = idOf(createBond(cara))
        submit(cara)
        closeThrough(1)
        clock.set(at(2))
        submit(cara)

        streak(cara).numbers() shouldBe Numbers(current = 0, longest = 0, freezes = 0, progress = 0, total = 0)
        streak(cara)["days"].size() shouldBe 0
    }

    /** Spec §12.4: days from before the pairing are private. Their dates are the days the creator wrote alone. */
    @Test
    fun `the calendar starts on the day the bond became two people`() {
        clear()
        val cara = users.verified("Cara")
        val dan = users.verified("Dan")
        clock.set(at(1))
        val created = createBond(cara)
        bond = idOf(created)
        submit(cara)
        clock.set(at(2))
        submit(cara)
        clock.set(at(3))
        accept(dan, codeOf(created))
        submit(cara)
        submit(dan)

        for (member in listOf(cara, dan)) {
            streak(member)["days"].toList().map { it["date"].asString() } shouldBe listOf(day(3).toString())
        }
    }

    @Test
    fun `the calendar goes back fifty-three weeks and no further`() {
        clock.set(at(400))
        closer.closeElapsedDays(at(400), 1_000)
        closer.closeElapsedDays(at(400), 1_000).failed shouldBe 0

        val days = streak(ada)["days"]

        // Today has no row yet, so the newest square is yesterday and the oldest is 370 days before today.
        days.first()["date"].asString() shouldBe day(400).minusDays(370).toString()
        days.last()["date"].asString() shouldBe day(399).toString()
        days.size() shouldBe 370
    }

    // --- what the payload says, and to whom ---

    /**
     * FR-076: the system never tells one member that the other has not
     * written. These payloads are counts about the bond and a square per day
     * from a vocabulary that cannot say one of two wrote. A field about a
     * member would be a new disclosure, so the fields are named here, all of
     * them.
     */
    @Test
    fun `the streak payloads carry these fields and no other`() {
        bothWriteOn(1)

        val streak = streak(ada)

        streak.propertyNames().toSet() shouldBe
            setOf("current", "longest", "freezesAvailable", "freezeProgress", "strictMode", "totalCompleteDays", "lastCompleteDate", "days")
        streak["days"].first().propertyNames().toSet() shouldBe setOf("date", "status")
        today(ada)["streak"].propertyNames().toSet() shouldBe setOf("current", "longest", "freezesAvailable", "strictMode")
    }

    @Test
    fun `Strict mode is reported as the bond has it now`() {
        mockMvc
            .patch("/api/v1/bonds/$bond") {
                header(HttpHeaders.AUTHORIZATION, bearer(bea))
                header(HttpHeaders.IF_MATCH, get(bea, "/api/v1/bonds/$bond").getHeader(HttpHeaders.ETAG)!!)
                contentType = MediaType.APPLICATION_JSON
                content = """{"strictMode":true}"""
            }.andReturn()
            .response.status shouldBe 200

        streak(ada)["strictMode"].asBoolean() shouldBe true
        today(ada)["streak"]["strictMode"].asBoolean() shouldBe true
    }

    @Test
    fun `a stranger gets the one 404, and a member who has left can still read the record`() {
        (1..2).forEach { bothWriteOn(it) }
        closeThrough(2)
        val eve = users.verified("Eve")

        val refused = get(eve, "/api/v1/bonds/$bond/streak")
        refused.status shouldBe 404
        refused.contentAsString.replace("/streak", "/today") shouldBe get(eve, "/api/v1/bonds/$bond/today").contentAsString
        get(ada, "/api/v1/bonds/not-an-id/streak").status shouldBe 404

        clock.set(at(3))
        leave(bea)
        streak(bea).numbers() shouldBe Numbers(current = 2, longest = 2, freezes = 0, progress = 2, total = 2)
    }

    // ---- helpers ----------------------------------------------------------

    private data class Numbers(
        val current: Int,
        val longest: Int,
        val freezes: Int,
        val progress: Int,
        val total: Int,
    )

    private fun JsonNode.numbers() =
        Numbers(
            this["current"].asInt(),
            this["longest"].asInt(),
            this["freezesAvailable"].asInt(),
            this["freezeProgress"].asInt(),
            this["totalCompleteDays"].asInt(),
        )

    private fun calendar(member: UUID): List<String> =
        streak(member)["days"].toList().map { "${it["date"].asString()} ${it["status"].asString()}" }

    private fun leave(member: UUID) {
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(member)) }
            .andReturn()
            .response.status shouldBe 204
    }

    private fun streak(member: UUID): JsonNode = read(get(member, "/api/v1/bonds/$bond/streak"))

    private fun today(member: UUID): JsonNode = read(get(member, "/api/v1/bonds/$bond/today"))

    private fun read(response: MockHttpServletResponse): JsonNode {
        response.status shouldBe 200
        return json.readTree(response.contentAsString)
    }

    private fun get(
        member: UUID,
        path: String,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) { header(HttpHeaders.AUTHORIZATION, bearer(member)) }
            .andReturn()
            .response

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n.toLong())

    private fun at(n: Int): Instant = Instant.parse("${day(n)}T10:00:00Z")

    private fun closeThrough(n: Int) {
        closer.closeElapsedDays(Instant.parse("${day(n)}T23:01:00Z"), 1_000).failed shouldBe 0
    }

    private fun bothWriteOn(n: Int) {
        clock.set(at(n))
        submit(ada)
        submit(bea)
    }

    private fun submit(caller: UUID) {
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"thank you"}"""
            }.andReturn()
            .response.status shouldBe 201
    }

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
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
}
