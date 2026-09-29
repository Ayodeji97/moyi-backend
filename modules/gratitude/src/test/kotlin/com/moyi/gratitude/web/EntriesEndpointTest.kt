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
@Import(EntriesEndpointTest.TimeConfiguration::class)
internal class EntriesEndpointTest(
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
        clock.set(NOW)
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        eve = users.verified("Eve")
        cara = users.verified("Cara")

        val created = createBond(ada)
        bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a member writes one entry, and the day is theirs by the bond's calendar`() {
        val response = submit(ada, bondId, """{"text":"thank you for the coffee"}""")

        response.status shouldBe 201
        response.contentAsString shouldContain "\"status\":\"SUBMITTED\""
        jdbc.queryForObject("SELECT date::text FROM bond_days", String::class.java) shouldBe "2026-09-15"
    }

    @Test
    fun `a second entry the same day is 409, and the first is untouched`() {
        submit(ada, bondId, """{"text":"first"}""").status shouldBe 201

        val second = submit(ada, bondId, """{"text":"second"}""")

        second.status shouldBe 409
        second.contentAsString shouldContain "\"code\":\"ENTRY_ALREADY_EXISTS\""
        jdbc.queryForObject("SELECT text FROM entries", String::class.java) shouldBe "first"
    }

    @Test
    fun `a non-member, an unknown bond and a value that is not a uuid are one answer`() {
        val bodies =
            listOf(
                submit(eve, bondId, """{"text":"hello"}"""),
                submit(ada, UUID.randomUUID().toString(), """{"text":"hello"}"""),
                submit(ada, "not-a-uuid", """{"text":"hello"}"""),
            )

        bodies.forEach { it.status shouldBe 404 }
        bodies.map { normalise(it.contentAsString) }.toSet().size shouldBe 1
    }

    @Test
    fun `an archived bond takes no entries, and a member who left is refused too`() {
        leave(ada, bondId).status shouldBe 204

        val adasAttempt = submit(ada, bondId, """{"text":"one more"}""")
        adasAttempt.status shouldBe 409
        adasAttempt.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""

        val beasAttempt = submit(bea, bondId, """{"text":"one more"}""")
        beasAttempt.status shouldBe 409
        beasAttempt.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }

    @Test
    fun `a media id is refused rather than ignored, until Phase 4 can honour it`() {
        val response = submit(ada, bondId, """{"text":"look","imageMediaId":"${UUID.randomUUID()}"}""")

        response.status shouldBe 422
        response.contentAsString shouldContain "\"code\":\"MEDIA_NOT_YET_SUPPORTED\""
    }

    /**
     * Fix round 1, I6: the same refusal for the other media field —
     * `SubmitEntry`'s check is `imageMediaId != null || voiceMediaId !=
     * null`, not just the first half of it.
     */
    @Test
    fun `a voice media id is refused too, not only an image one`() {
        val response = submit(ada, bondId, """{"text":"listen","voiceMediaId":"${UUID.randomUUID()}"}""")

        response.status shouldBe 422
        response.contentAsString shouldContain "\"code\":\"MEDIA_NOT_YET_SUPPORTED\""
    }

    /**
     * Fix round 1, I6: BR-3a is wired correctly — `DayAssignment.dateFor`'s
     * `isClosed` lambda is null-safe and the offline-window/clock-skew limits
     * are Task 4's own pure unit tests — but nothing before this exercised
     * `SubmitEntryRequest.intendedAt` through the actual HTTP request, so a
     * broken link between the wire field and `EntryDraft.intendedAt` (a typo
     * in the Jackson property name, say) would have passed every existing
     * test in this suite.
     */
    @Test
    fun `an offline draft's intendedAt files the entry against that earlier day`() {
        // 20 hours before NOW: inside the 36h offline window, not after
        // NOW plus the 5-minute clock-skew allowance — trusted. NOW is
        // 2026-09-15T10:00:00Z (2026-09-15 11:00 Lagos); 20 hours earlier is
        // 2026-09-14T14:00:00Z (2026-09-14 15:00 Lagos) — a day *before* the
        // one `submittedAt` alone would have filed this under.
        val intendedAt = NOW.minus(Duration.ofHours(20))

        val response = submit(ada, bondId, """{"text":"written on the flight","intendedAt":"$intendedAt"}""")

        response.status shouldBe 201
        jdbc.queryForObject("SELECT date::text FROM bond_days", String::class.java) shouldBe "2026-09-14"
    }

    @Test
    fun `an intendedAt past the 36h offline window is ignored, and the entry lands on today`() {
        val tooOld = NOW.minus(Duration.ofHours(40))

        val response = submit(ada, bondId, """{"text":"too old to back-file","intendedAt":"$tooOld"}""")

        response.status shouldBe 201
        jdbc.queryForObject("SELECT date::text FROM bond_days", String::class.java) shouldBe "2026-09-15"
    }

    @Test
    fun `the creator may write before anybody joins, and that day is suspended`() {
        // 02 J1: never gate the creator on the invitee. Doc 04 §8.3a as the
        // Phase 3 design §12.4 resolves it — the row exists so the entry has
        // somewhere to live, SUSPENDED so the streak never counts it.
        val solo = createBond(cara)

        submit(cara, bondIdOf(solo), """{"text":"waiting for you"}""").status shouldBe 201

        jdbc.queryForObject("SELECT status FROM bond_days", String::class.java) shouldBe "SUSPENDED"
    }

    @Test
    fun `an entry of only whitespace is 422 naming the field, and never a 500`() {
        for (blank in listOf("", " ", "\\u00a0")) {
            val response = submit(ada, bondId, """{"text":"$blank"}""")
            response.status shouldBe 422
            response.contentAsString shouldContain "\"field\":\"text\""
        }
    }

    /**
     * Fix round 1, C1: the edge's first draft restated FR-041's octet limit
     * with `@Size(max = 8192)`, which counts UTF-16 characters, not UTF-8
     * octets — so a body that is under the character count but over the real
     * byte count sailed past the edge and reached [com.moyi.gratitude.domain.EntryText.of]
     * as an uncaught `IllegalArgumentException`, a `500` rather than FR-041's
     * own `422`. `[ValidEntryText]` closes that by asking the domain instead
     * of restating it — proven here on both limits it enforces.
     */
    @Test
    fun `an entry over FR-041's limits is 422 naming the field, and never a 500`() {
        // 501 graphemes: passes the old (wrong-unit) edge check by a wide
        // margin, and was never checked for grapheme count at the edge at all.
        val tooManyGraphemes = "a".repeat(501)
        // 2,058 single-codepoint emoji: 4,116 UTF-16 characters (under the old
        // `@Size(max = 8192)`) but 8,232 UTF-8 octets (over the real cap) —
        // the exact shape of body the old edge check let through wrongly.
        val tooManyOctets = "😀".repeat(2058)

        for (tooLong in listOf(tooManyGraphemes, tooManyOctets)) {
            val response = submit(ada, bondId, """{"text":"$tooLong"}""")
            response.status shouldBe 422
            response.contentAsString shouldContain "\"field\":\"text\""
        }
    }

    /**
     * `DayClosedException` (`409 DAY_CLOSED`) is unreachable through any
     * write this slice's own code produces — C1 opens a day only `OPEN` or
     * `SUSPENDED`, and closes none. It exists for the row `SubmitEntry`
     * reads back from `BondDayStore.openOrGet` already existing and already
     * closed by the time a later slice's close job (C3) runs alongside this
     * one — so this test forces that state by hand, standing in for C3,
     * rather than leaving the guard proven only by reading it.
     */
    @Test
    fun `a day closed by a later slice's own writes refuses a new entry rather than silently accepting it`() {
        submit(ada, bondId, """{"text":"today"}""").status shouldBe 201
        jdbc.update("UPDATE bond_days SET status = 'REVEALED' WHERE bond_id = ?::uuid", bondId)

        val response = submit(bea, bondId, """{"text":"too late"}""")

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"DAY_CLOSED\""
    }

    /**
     * Context note C: `@Idempotent`'s caller identity comes from
     * `request.userPrincipal`, and until this endpoint existed it had only
     * ever been exercised against a `Principal` a test set by hand
     * (`common:web`'s own `IdempotencyInterceptorTest`). This is that
     * mechanism's first run through the *real* filter chain: a real signed
     * JWT, `SecurityContextHolderAwareRequestFilter` populating
     * `userPrincipal` from it, and `JwtAuthenticationToken.getName()`
     * resolving to the `sub` claim `IdempotencyInterceptor.callerId` parses.
     *
     * Also proves the reservation is scoped to `userId + endpoint + key`
     * (doc 06 §1), not to the key alone: two different real callers reuse the
     * identical literal key without colliding.
     */
    @Test
    fun `a real bearer token identity scopes Idempotency-Key, and two callers may share one key`() {
        val key = UUID.randomUUID().toString()

        val first = submit(ada, bondId, """{"text":"only once"}""", key)
        first.status shouldBe 201

        val replay = submit(ada, bondId, """{"text":"only once"}""", key)
        replay.status shouldBe 201
        replay.contentAsString shouldBe first.contentAsString
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 1

        val beasTurn = submit(bea, bondId, """{"text":"me too"}""", key)
        beasTurn.status shouldBe 201
        beasTurn.getHeader(IdempotencyInterceptor.REPLAYED_HEADER).shouldBeNull()
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 2
    }

    // ---- helpers --------------------------------------------------------

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

    private fun leave(
        caller: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/leave") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
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
    private fun normalise(json: String): String = json.replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = NOW)
    }

    private companion object {
        /** Midday UTC on the 15th is midday-plus-one in Africa/Lagos — nowhere near a midnight boundary either side. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
    }
}
