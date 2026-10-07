package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
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
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Spec §12.4, the joining day: the day a second member arrives on is taken
 * out of `SUSPENDED` by the first gratitude operation that meets it, and no
 * earlier day ever is.
 *
 * Two kinds of joining day are driven here. A **legacy** one is what C1 left
 * behind before the reveal existed — `SUSPENDED` with both entries on it —
 * and is built by hand in [legacyJoiningDay], because no request can produce
 * it any more. A **native** one is produced by the requests themselves, with
 * no row touched by hand.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(JoiningDayTest.TimeConfiguration::class)
internal class JoiningDayTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var cara: UUID
    private lateinit var eve: UUID

    @BeforeEach
    fun setUp() {
        cara = users.verified("Cara")
        eve = users.verified("Eve")
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    // --- a legacy joining day: every first operation reconciles it, and the reconcile outlives a refusal ---

    @Test
    fun `a first favourite PUT by the partner reconciles a legacy joining day, and is then a mark like any other`() {
        // Without the reconcile the entry is still locked to him, and the answer is the 404 of an entry never shown.
        val legacy = legacyJoiningDay()

        favourite(HttpMethod.PUT, eve, legacy.caraEntry).status shouldBe 204

        legacy.shouldBeRevealedOnce()
        jdbc.queryForObject("SELECT count(*) FROM entry_favourites WHERE entry_id = ?::uuid", Int::class.java, legacy.caraEntry) shouldBe 1
    }

    @Test
    fun `a first favourite DELETE by the partner reconciles a legacy joining day too`() {
        val legacy = legacyJoiningDay()

        favourite(HttpMethod.DELETE, eve, legacy.caraEntry).status shouldBe 204

        legacy.shouldBeRevealedOnce()
    }

    @Test
    fun `a refused first PATCH still leaves a legacy joining day revealed`() {
        val legacy = legacyJoiningDay()

        val refused = patchEntry(cara, legacy.caraEntry, """{"text":"too late"}""")

        refused.status shouldBe 409
        refused.contentAsString shouldContain "ENTRY_IMMUTABLE"
        legacy.shouldBeRevealedOnce()
    }

    @Test
    fun `a refused first keyed PATCH still leaves a legacy joining day revealed`() {
        val legacy = legacyJoiningDay()

        val refused = patchEntry(cara, legacy.caraEntry, """{"text":"too late"}""", key = UUID.randomUUID().toString())

        refused.status shouldBe 409
        legacy.shouldBeRevealedOnce()
    }

    @Test
    fun `a first DELETE reconciles a legacy joining day and keeps the reveal on the erased entry`() {
        val legacy = legacyJoiningDay()

        deleteEntry(cara, legacy.caraEntry).status shouldBe 204

        legacy.shouldBeRevealedOnce()
        today(eve, legacy.bondId).contentAsString shouldNotContain "\"text\":\"joining words\""
    }

    @Test
    fun `a refused first POST still leaves a legacy joining day revealed`() {
        val legacy = legacyJoiningDay()

        // Same instant, so it resolves to the joining day itself — which the
        // reconcile has just revealed, and a revealed day takes no entry.
        val refused = submit(cara, legacy.bondId, """{"text":"again"}""")

        refused.status shouldBe 409
        legacy.shouldBeRevealedOnce()
    }

    @Test
    fun `a first POST replay reconciles a legacy joining day and reads the entry as revealed`() {
        val legacy = legacyJoiningDay()

        val replay = submit(cara, legacy.bondId, """{"text":"joining words"}""", legacy.caraKey)

        replay.status shouldBe 201
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        replay.contentAsString shouldContain "\"status\":\"REVEALED\""
        legacy.shouldBeRevealedOnce()
    }

    // --- a native joining day: no row is touched by hand ---

    @Test
    fun `the second member's first entry on the joining day resumes it and reveals`() {
        val created = createBond(cara)
        val bondId = idOf(created)
        submit(cara, bondId, """{"text":"written while waiting"}""").status shouldBe 201
        statusOf(bondId, "2026-09-15") shouldBe "SUSPENDED"
        clock.set(NOW.plusSeconds(3600))
        accept(eve, codeOf(created)).status shouldBe 200

        submit(eve, bondId, """{"text":"written on arriving"}""").status shouldBe 201

        statusOf(bondId, "2026-09-15") shouldBe "REVEALED"
        revealedEntries(bondId) shouldBe 2
        dayRevealedEvents() shouldBe 1
        today(eve, bondId).contentAsString shouldContain "written while waiting"
        today(cara, bondId).contentAsString shouldContain "written on arriving"
    }

    @Test
    fun `pairing on a day one member wrote on makes it PARTIAL at the first read, and locks nothing open`() {
        val created = createBond(cara)
        val bondId = idOf(created)
        submit(cara, bondId, """{"text":"written while waiting"}""").status shouldBe 201
        accept(eve, codeOf(created)).status shouldBe 200

        val seen = today(eve, bondId)

        seen.contentAsString shouldContain "\"status\":\"PARTIAL\""
        seen.contentAsString shouldContain "\"status\":\"LOCKED\""
        seen.contentAsString shouldNotContain "written while waiting"
        statusOf(bondId, "2026-09-15") shouldBe "PARTIAL"
        dayRevealedEvents() shouldBe 0
    }

    @Test
    fun `pairing on a day whose only entry was deleted makes it OPEN`() {
        val created = createBond(cara)
        val bondId = idOf(created)
        val entry = idOf(submit(cara, bondId, """{"text":"written while waiting"}"""))
        deleteEntry(cara, entry).status shouldBe 204
        accept(eve, codeOf(created)).status shouldBe 200

        today(eve, bondId).status shouldBe 200

        statusOf(bondId, "2026-09-15") shouldBe "OPEN"
        jdbc.queryForObject("SELECT entry_count FROM bond_days WHERE bond_id = ?::uuid", Int::class.java, bondId) shouldBe 0
        dayRevealedEvents() shouldBe 0
    }

    // --- a day from before the pairing stays private, whether or not it had a row ---

    @Test
    fun `back-filling a day from before the pairing does not reveal it when it had no row`() {
        clock.set(DAY_BEFORE)
        val created = createBond(cara)
        val bondId = idOf(created)
        clock.set(NOW)
        accept(eve, codeOf(created)).status shouldBe 200

        submit(cara, bondId, """{"text":"hers, from yesterday","intendedAt":"$YESTERDAY_EVENING"}""").status shouldBe 201
        submit(eve, bondId, """{"text":"his, from yesterday","intendedAt":"$YESTERDAY_EVENING"}""").status shouldBe 201

        statusOf(bondId, "2026-09-14") shouldBe "SUSPENDED"
        revealedEntries(bondId) shouldBe 0
        dayRevealedEvents() shouldBe 0
    }

    @Test
    fun `back-filling a day from before the pairing does not reveal it when it had a row`() {
        clock.set(DAY_BEFORE)
        val created = createBond(cara)
        val bondId = idOf(created)
        submit(cara, bondId, """{"text":"hers, written yesterday"}""").status shouldBe 201
        clock.set(NOW)
        accept(eve, codeOf(created)).status shouldBe 200

        submit(eve, bondId, """{"text":"his, from yesterday","intendedAt":"$YESTERDAY_EVENING"}""").status shouldBe 201

        statusOf(bondId, "2026-09-14") shouldBe "SUSPENDED"
        revealedEntries(bondId) shouldBe 0
        dayRevealedEvents() shouldBe 0
    }

    private fun statusOf(
        bondId: String,
        date: String,
    ): String? =
        jdbc.queryForObject("SELECT status FROM bond_days WHERE bond_id = ?::uuid AND date = ?::date", String::class.java, bondId, date)

    private fun revealedEntries(bondId: String): Int? =
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND revealed_at IS NOT NULL", Int::class.java, bondId)

    private fun dayRevealedEvents(): Int? =
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'DayRevealed'", Int::class.java)

    private data class Legacy(
        val bondId: String,
        val caraEntry: String,
        val caraKey: String,
    )

    /**
     * What C1 committed for a couple who both wrote on the day they paired:
     * the day `SUSPENDED` at `entry_count = 2`, neither entry revealed, no
     * event. The two submissions are real; the reset is by hand.
     */
    private fun legacyJoiningDay(): Legacy {
        val created = createBond(cara)
        val bondId = idOf(created)
        val caraKey = UUID.randomUUID().toString()
        val caraEntry = idOf(submit(cara, bondId, """{"text":"joining words"}""", caraKey).also { it.status shouldBe 201 })
        accept(eve, codeOf(created)).status shouldBe 200
        submit(eve, bondId, """{"text":"partner joining words"}""").status shouldBe 201
        jdbc.update("UPDATE bond_days SET status = 'SUSPENDED', revealed_at = NULL WHERE bond_id = ?::uuid", bondId)
        jdbc.update("UPDATE entries SET status = 'SUBMITTED', revealed_at = NULL WHERE bond_id = ?::uuid", bondId)
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        return Legacy(bondId, caraEntry, caraKey)
    }

    private fun Legacy.shouldBeRevealedOnce() {
        jdbc.queryForObject("SELECT status FROM bond_days WHERE bond_id = ?::uuid", String::class.java, bondId) shouldBe "REVEALED"
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND revealed_at IS NOT NULL",
            Int::class.java,
            bondId,
        ) shouldBe 2
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'DayRevealed'", Int::class.java) shouldBe 1
    }

    private fun favourite(
        method: HttpMethod,
        user: UUID,
        entryId: String,
    ): MockHttpServletResponse =
        mockMvc
            .perform(
                MockMvcRequestBuilders
                    .request(method, "/api/v1/entries/$entryId/favourite")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}"),
            ).andReturn()
            .response

    private fun patchEntry(
        user: UUID,
        id: String,
        body: String,
        key: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                if (key != null) header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun deleteEntry(
        user: UUID,
        id: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
            }.andReturn()
            .response

    private fun today(
        user: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId/today") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
            }.andReturn()
            .response

    private fun submit(
        caller: UUID,
        bondId: String,
        body: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
                header(IdempotencyInterceptor.HEADER, idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
            .andReturn()
            .response

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
        /** Midday UTC on the 15th is 11:00 in Africa/Lagos — nowhere near a midnight either side. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        /** The same hour a day earlier: Bond-day 2026-09-14, and when the bond in the back-fill tests is created. */
        val DAY_BEFORE: Instant = Instant.parse("2026-09-14T10:00:00Z")

        /** Later on 2026-09-14 in Lagos, after [DAY_BEFORE] — a claim the bond already existed for. */
        const val YESTERDAY_EVENING = "2026-09-14T18:00:00Z"
    }
}
