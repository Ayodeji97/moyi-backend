package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.nulls.shouldBeNull
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `POST /bonds/{bondId}/entries`, through the real chain against a real
 * Postgres — `BondsEndpointTest`'s own precedent, and the first HTTP test in
 * `gratitude`: [GratitudeTestApplication] now scans `com.moyi.bond` too, so
 * [createBond] and [leave] below drive the **real** bond endpoints rather
 * than a fake stood up to dodge them (see that class's KDoc for why).
 *
 * The clock is pinned by [TimeConfiguration] so every entry this suite files
 * lands on one predictable calendar date, `2026-09-15` in `Africa/Lagos` —
 * every `submit` below runs at the same instant unless a test says otherwise.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension::class)
internal class EntryChangesTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var eve: UUID
    private lateinit var cara: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        eve = users.verified("Eve")
        cara = users.verified("Cara")

        // Created two days before NOW, so an offline draft from yesterday
        // names a time the bond already existed — a bond's calendar starts at
        // its creation, and a claim from before then is refused (see the
        // pre-creation test below).
        clock.set(BOND_CREATED)
        val created = createBond(ada)
        bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `an author edits before reveal and cannot edit after it`() {
        val id = entryId(submit(ada, bondId, """{"text":"before"}"""))
        patchEntry(ada, id, """{"text":"after"}""").status shouldBe 200
        jdbc.queryForObject("SELECT text FROM entries", String::class.java) shouldBe "after"
        submit(bea, bondId, """{"text":"partner"}""").status shouldBe 201
        val refused = patchEntry(ada, id, """{"text":"too late"}""")
        refused.status shouldBe 409
        refused.contentAsString shouldContain "ENTRY_IMMUTABLE"
    }

    @Test
    fun `delete frees the live slot and today picks the replacement`() {
        val id = entryId(submit(ada, bondId, """{"text":"removed words"}"""))
        deleteEntry(ada, id).status shouldBe 204
        deleteEntry(ada, id).status shouldBe 204
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE status = 'DELETED' AND deleted_at IS NOT NULL AND text IS NULL",
            Int::class.java,
        ) shouldBe
            1
        submit(ada, bondId, """{"text":"replacement words"}""").status shouldBe 201
        val today = today(ada)
        today.contentAsString shouldContain "replacement words"
        today.contentAsString shouldNotContain "removed words"
        jdbc.queryForObject("SELECT entry_count FROM bond_days", Int::class.java) shouldBe 1
    }

    @Test
    fun `post-reveal delete preserves the reveal timestamp and authoritative day`() {
        val id = entryId(submit(ada, bondId, """{"text":"removed words"}"""))
        submit(bea, bondId, """{"text":"partner words"}""").status shouldBe 201
        deleteEntry(ada, id).status shouldBe 204
        jdbc.queryForObject("SELECT status FROM bond_days", String::class.java) shouldBe "REVEALED"
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE id = ?::uuid AND revealed_at IS NOT NULL AND deleted_at IS NOT NULL",
            Int::class.java,
            id,
        ) shouldBe
            1
        today(bea).contentAsString shouldNotContain "removed words"
    }

    @Test
    fun `entry routes hide another author and another tenant exactly like a missing id`() {
        val id = entryId(submit(ada, bondId, """{"text":"private"}"""))
        listOf(bea to id, eve to id, ada to UUID.randomUUID().toString(), ada to "bad-id").forEach { (caller, target) ->
            patchEntry(caller, target, """{"text":"edit"}""").status shouldBe 404
            deleteEntry(caller, target).status shouldBe 404
        }
    }

    @Test
    fun `patch asks the same domain text validator and refuses unsupported media`() {
        val id = entryId(submit(ada, bondId, """{"text":"private"}"""))
        patchEntry(ada, id, """{"text":" "}""").status shouldBe 422
        patchEntry(ada, id, """{"text":"${"a".repeat(501)}"}""").status shouldBe 422
        patchEntry(ada, id, """{"text":"ok","imageMediaId":"${UUID.randomUUID()}"}""").status shouldBe 422
        patchEntry(ada, id, "{}").status shouldBe 422
    }

    @Test
    fun `a real update constraint failure never exposes entry text`(output: org.springframework.boot.test.system.CapturedOutput) {
        val id = entryId(submit(ada, bondId, """{"text":"privacy-canary-before"}"""))
        jdbc.execute("ALTER TABLE entries ADD CONSTRAINT c2_privacy_probe CHECK (updated_at = created_at)")
        try {
            clock.set(NOW.plusSeconds(1))
            val response = patchEntry(ada, id, """{"text":"privacy-canary-after"}""")
            response.status shouldBe 500
            response.contentAsString shouldNotContain "privacy-canary"
            output.all shouldNotContain "privacy-canary"
            output.all shouldNotContain "Failing row contains"
            output.all shouldContain "c2_privacy_probe"
        } finally {
            jdbc.execute("ALTER TABLE entries DROP CONSTRAINT c2_privacy_probe")
        }
    }

    @Test
    fun `the first read after pairing reconciles an elapsed joining day`() {
        val created = createBond(cara)
        val pendingId = bondIdOf(created)
        submit(cara, pendingId, """{"text":"joining words"}""").status shouldBe 201
        accept(eve, codeOf(created)).status shouldBe 200
        // Simulate two C1 submissions persisted before C2 was deployed.
        submit(eve, pendingId, """{"text":"partner joining words"}""").status shouldBe 201
        jdbc.update("UPDATE bond_days SET status = 'SUSPENDED', revealed_at = NULL WHERE bond_id = ?::uuid", pendingId)
        jdbc.update("UPDATE entries SET status = 'SUBMITTED', revealed_at = NULL WHERE bond_id = ?::uuid", pendingId)
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        clock.set(NOW.plusSeconds(86400))
        mockMvc
            .get("/api/v1/bonds/$pendingId/today") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(cara).token}")
            }.andReturn()
            .response.status shouldBe 200
        jdbc.queryForObject("SELECT status FROM bond_days WHERE bond_id = ?::uuid", String::class.java, pendingId) shouldBe "REVEALED"
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND revealed_at IS NOT NULL",
            Int::class.java,
            pendingId,
        ) shouldBe
            2
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'DayRevealed'", Int::class.java) shouldBe 1
    }

    private fun entryId(response: MockHttpServletResponse): String = bondIdOf(response)

    private fun patchEntry(
        user: UUID,
        id: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
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

    private fun today(user: UUID): MockHttpServletResponse =
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

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    /** `instance` is the path the caller typed, theirs to see (the lesson from #35's 404 body) — `BondCrossTenantTest`'s own helper. */

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = NOW)
    }

    private companion object {
        /** Midday UTC on the 15th is midday-plus-one in Africa/Lagos — nowhere near a midnight boundary either side. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        /** Two days before [NOW] — see [setUp]. */
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
    }
}
