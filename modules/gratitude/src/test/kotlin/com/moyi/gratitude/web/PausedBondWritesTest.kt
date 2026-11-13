package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
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
import org.springframework.test.web.servlet.put
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * A paused bond (admin A3, spec §6 and §9): while a member who has not left is suspended, the
 * partner's new entry, edit and reaction are `409 BOND_PAUSED` and nothing is written; everything
 * else they could do, they still can. The suspended member is `ada` throughout; `bea` is the
 * partner who acts.
 *
 * Each test's comment names the change that makes it fail.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(PausedBondWritesTest.TimeConfiguration::class)
internal class PausedBondWritesTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired standing: AccountStanding,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val suspensions = standing as FakeAccountStanding
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        clock.set(NOW)
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        val created = createBond(ada)
        bondId = idOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
    }

    @AfterEach
    fun clear() {
        suspensions.reset()
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    // ---- refused ----------------------------------------------------------

    @Test
    fun `a new entry is refused while the bond is paused, and nothing is written`() {
        // Drop the check from SubmitEntry and the partner's entry is a 201.
        suspensions.suspend(ada, NOW)

        val refused = submit(bea, """{"text":"hello"}""")

        refused.status shouldBe 409
        refused.contentAsString shouldContain "\"code\":\"BOND_PAUSED\""
        refused.contentAsString shouldContain PAUSED_DETAIL
        count("SELECT count(*) FROM entries") shouldBe 0
    }

    @Test
    fun `a late entry for yesterday is refused the same way`() {
        // The check sits before the day is chosen, so a late entry is no way round it.
        suspensions.suspend(ada, NOW)

        val refused = submit(bea, """{"text":"late","intendedAt":"2026-09-14T22:30:00Z"}""")

        refused.status shouldBe 409
        refused.contentAsString shouldContain "\"code\":\"BOND_PAUSED\""
        count("SELECT count(*) FROM entries") shouldBe 0
    }

    @Test
    fun `an edit, with or without media, is refused and the entry is untouched`() {
        // Drop the check from ChangeEntry and the text is replaced.
        // Her own entry before the partner has written: once both have, an entry is immutable.
        val mine = idOf(submit(bea, """{"text":"hello from bea"}""").also { it.status shouldBe 201 })
        val before = jdbc.queryForMap("SELECT text, updated_at FROM entries WHERE id = ?::uuid", mine)
        suspensions.suspend(ada, NOW)
        clock.set(NOW.plusSeconds(60))

        for (body in listOf("""{"text":"changed"}""", """{"text":"changed","imageMediaId":"${UUID.randomUUID()}"}""")) {
            val refused = patchEntry(bea, mine, body)
            refused.status shouldBe 409
            refused.contentAsString shouldContain "\"code\":\"BOND_PAUSED\""
            refused.contentAsString shouldContain PAUSED_DETAIL
        }

        jdbc.queryForMap("SELECT text, updated_at FROM entries WHERE id = ?::uuid", mine) shouldBe before
    }

    @Test
    fun `a reaction is refused and no row is written`() {
        // Drop the check from ReactToEntry.react and the reaction is a 200.
        val (adas, _) = bothWrite()
        suspensions.suspend(ada, NOW)

        val refused = react(bea, adas)

        refused.status shouldBe 409
        refused.contentAsString shouldContain "\"code\":\"BOND_PAUSED\""
        refused.contentAsString shouldContain PAUSED_DETAIL
        count("SELECT count(*) FROM reactions") shouldBe 0
    }

    @Test
    fun `an ended bond answers BOND_ARCHIVED first, for each of the three`() {
        // The paused check belongs after the archived one: swap them and these say BOND_PAUSED.
        val (adas, mine) = bothWrite()
        suspensions.suspend(ada, NOW)
        // Ada, suspended, is still a member who can leave: the bond ends, and is no longer paused.
        post("/api/v1/bonds/$bondId/leave", ada).status shouldBe 204

        for (response in listOf(
            submit(bea, """{"text":"hello"}"""),
            patchEntry(bea, mine, """{"text":"changed"}"""),
            react(bea, adas),
        )) {
            response.status shouldBe 409
            response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
            response.contentAsString shouldNotContain "BOND_PAUSED"
        }
    }

    // ---- still allowed ------------------------------------------------------

    @Test
    fun `what the partner must always be able to do is still allowed while paused`() {
        val (adas, mine) = bothWrite()
        react(bea, adas).status shouldBe 204
        suspensions.suspend(ada, NOW)

        delete("/api/v1/entries/$adas/reaction", bea).status shouldBe 204
        put("/api/v1/entries/$adas/favourite", bea).status shouldBe 204
        delete("/api/v1/entries/$adas/favourite", bea).status shouldBe 204
        mockMvc
            .post("/api/v1/entries/$adas/report") {
                header(HttpHeaders.AUTHORIZATION, bearer(bea))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"reason":"HARASSMENT"}"""
            }.andReturn()
            .response.status shouldBe 201
        get("/api/v1/bonds/$bondId/days", bea).status shouldBe 200
        get("/api/v1/bonds/$bondId/today", bea).status shouldBe 200
        get("/api/v1/bonds/$bondId/search?q=hello", bea).status shouldBe 200
        put("/api/v1/bonds/$bondId/members/me/settings", bea, """{"reminderTimeLocal":"07:30"}""").status shouldBe 200
        val read = get("/api/v1/bonds/$bondId", bea)
        read.status shouldBe 200
        mockMvc
            .patch("/api/v1/bonds/$bondId") {
                header(HttpHeaders.AUTHORIZATION, bearer(bea))
                header(HttpHeaders.IF_MATCH, read.getHeader(HttpHeaders.ETAG)!!)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Renamed"}"""
            }.andReturn()
            .response.status shouldBe 200
        // The author's own erasure (BR-10a).
        delete("/api/v1/entries/$mine", bea).status shouldBe 204
        // Leaving, last: it ends the bond.
        post("/api/v1/bonds/$bondId/leave", bea).status shouldBe 204
    }

    // ---- replay ---------------------------------------------------------------

    @Test
    fun `a submit accepted before the pause answers its original 201 when replayed after it`() {
        // Put SubmitEntry's check before `execution.once` and the replay is a 409.
        val key = UUID.randomUUID().toString()
        val first = submit(bea, """{"text":"hello"}""", key)
        first.status shouldBe 201
        suspensions.suspend(ada, NOW)

        val replay = submit(bea, """{"text":"hello"}""", key)
        replay.status shouldBe 201
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        idOf(replay) shouldBe idOf(first)

        val fresh = submit(bea, """{"text":"hello"}""")
        fresh.status shouldBe 409
        fresh.contentAsString shouldContain "\"code\":\"BOND_PAUSED\""
        count("SELECT count(*) FROM entries") shouldBe 1
    }

    // ---- today and the lift -------------------------------------------------------

    @Test
    fun `today says paused and gives no prompt, and says neither once lifted`() {
        // Drop the DailyPrompt clause and the prompt stays; drop the field and paused is absent.
        val live = get("/api/v1/bonds/$bondId/today", bea).contentAsString
        live shouldContain "\"paused\":false"
        live shouldContain "\"prompt\":{"

        suspensions.suspend(ada, NOW)
        val paused = get("/api/v1/bonds/$bondId/today", bea).contentAsString
        paused shouldContain "\"paused\":true"
        paused shouldContain "\"prompt\":null"

        suspensions.lift(ada, NOW.plusSeconds(60))
        val lifted = get("/api/v1/bonds/$bondId/today", bea).contentAsString
        lifted shouldContain "\"paused\":false"
        lifted shouldContain "\"prompt\":{"
    }

    @Test
    fun `the same write succeeds once the bond resumes`() {
        suspensions.suspend(ada, NOW)
        submit(bea, """{"text":"hello"}""").status shouldBe 409

        suspensions.lift(ada, NOW.plusSeconds(60))

        submit(bea, """{"text":"hello"}""").status shouldBe 201
    }

    // ---- helpers ----------------------------------------------------------------

    /** Both write today, so the entries are revealed: returns Ada's and Bea's entry ids. */
    private fun bothWrite(): Pair<String, String> {
        val adas = idOf(submit(ada, """{"text":"hello from ada"}""").also { it.status shouldBe 201 })
        val mine = idOf(submit(bea, """{"text":"hello from bea"}""").also { it.status shouldBe 201 })
        return adas to mine
    }

    private fun bearer(user: UUID) = "Bearer ${tokens.issue(user).token}"

    private fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!

    private fun submit(
        caller: UUID,
        body: String,
        key: String = UUID.randomUUID().toString(),
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun patchEntry(
        user: UUID,
        id: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, bearer(user))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun react(
        user: UUID,
        entryId: String,
    ): MockHttpServletResponse = put("/api/v1/entries/$entryId/reaction", user, """{"type":"HEART"}""")

    private fun get(
        path: String,
        user: UUID,
    ): MockHttpServletResponse = mockMvc.get(path) { header(HttpHeaders.AUTHORIZATION, bearer(user)) }.andReturn().response

    private fun put(
        path: String,
        user: UUID,
        body: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .put(path) {
                header(HttpHeaders.AUTHORIZATION, bearer(user))
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
            }.andReturn()
            .response

    private fun post(
        path: String,
        user: UUID,
        body: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                header(HttpHeaders.AUTHORIZATION, bearer(user))
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
            }.andReturn()
            .response

    private fun delete(
        path: String,
        user: UUID,
    ): MockHttpServletResponse = mockMvc.delete(path) { header(HttpHeaders.AUTHORIZATION, bearer(user)) }.andReturn().response

    private fun createBond(userId: UUID): MockHttpServletResponse =
        post("/api/v1/bonds", userId, """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}""")

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse = post("/api/v1/invites/$code/accept", userId)

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun idOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = NOW)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        const val PAUSED_DETAIL = "This bond is paused. Everything in it can still be read; new entries wait until it resumes."
    }
}
