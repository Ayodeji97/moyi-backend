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
import org.springframework.test.web.servlet.get
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
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
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
        // 20 hours before NOW: inside the 36h offline window and not ahead
        // of NOW (ruling P11) — trusted. NOW is
        // 2026-09-15T10:00:00Z (2026-09-15 11:00 Lagos); 20 hours earlier is
        // 2026-09-14T14:00:00Z (2026-09-14 15:00 Lagos) — a day *before* the
        // one `submittedAt` alone would have filed this under.
        val intendedAt = NOW.minus(Duration.ofHours(20))

        val response = submit(ada, bondId, """{"text":"written on the flight","intendedAt":"$intendedAt"}""")

        response.status shouldBe 201
        jdbc.queryForObject("SELECT date::text FROM bond_days", String::class.java) shouldBe "2026-09-14"
    }

    @Test
    fun `an intendedAt from before the bond existed is ignored, not a 500`() {
        // Cara's bond is created at NOW; one hour earlier is inside BR-3a's
        // windows but before the bond's calendar begins. The anchor timeline
        // cannot place that instant, and must not be asked to: the claim
        // falls back to the submission instant like any other rejected one.
        val solo = createBond(cara)
        val beforeTheBond = NOW.minus(Duration.ofHours(1))

        val response = submit(cara, bondIdOf(solo), """{"text":"before we began","intendedAt":"$beforeTheBond"}""")

        response.status shouldBe 201
        val filedOn = jdbc.queryForObject("SELECT date::text FROM bond_days WHERE bond_id = ?::uuid", String::class.java, bondIdOf(solo))
        filedOn shouldBe "2026-09-15"
        intendedAtOf(response) shouldBe NOW
    }

    @Test
    fun `an intendedAt past the 36h offline window is ignored, and the entry lands on today`() {
        val tooOld = NOW.minus(Duration.ofHours(40))

        val response = submit(ada, bondId, """{"text":"too old to back-file","intendedAt":"$tooOld"}""")

        response.status shouldBe 201
        jdbc.queryForObject("SELECT date::text FROM bond_days", String::class.java) shouldBe "2026-09-15"
    }

    /**
     * F1, whole-branch review: `intended_at` must hold the instant
     * [com.moyi.gratitude.domain.DayAssignment.resolve] actually accepted,
     * never a raw client claim — three places said it already did
     * (`Entry.submit`'s own KDoc, V12's column comment, this method's own
     * behaviour before the fix) and only the third was false. `2099-01-01`
     * is ahead of `NOW` (2026-09-15), and a claim ahead of the server's
     * clock is never the candidate (ruling P11), so [DayAssignment.resolve] falls
     * back to `submittedAt` for *both* the date and the resolved instant —
     * before this fix, the raw claim was stored and echoed back regardless.
     */
    @Test
    fun `a claim DayAssignment rejects is never the value stored or echoed`() {
        val rejected = "2099-01-01T00:00:00Z"

        val response = submit(ada, bondId, """{"text":"clock skew","intendedAt":"$rejected"}""")

        response.status shouldBe 201
        response.contentAsString shouldContain "\"date\":\"2026-09-15\""
        intendedAtOf(response) shouldBe NOW
        jdbc.queryForObject("SELECT intended_at FROM entries", java.sql.Timestamp::class.java)!!.toInstant() shouldBe NOW
    }

    /**
     * `timestamptz` keeps microseconds; a client may send nanoseconds. The
     * claim is truncated where the server's own `now` is, before anything is
     * decided or stored, for two reasons this test holds one each:
     *
     * - a fresh `201` renders the instant from memory and a replay from the
     *   row, and the two must be the same bytes;
     * - pgjdbc *rounds* what it is handed, so the last half-microsecond of a
     *   day would be stored as the first instant of the next one — outside
     *   the span of the day the entry is filed on.
     *
     * Lagos's 14th ends at 14T23:00:00Z; the claim is 400 ns before that.
     */
    @Test
    fun `an intendedAt finer than a microsecond is truncated, so the row stays inside its day and a replay matches`() {
        val key = UUID.randomUUID().toString()
        val body = """{"text":"just before midnight","intendedAt":"2026-09-14T22:59:59.9999996Z"}"""

        val first = submit(ada, bondId, body, key)

        first.status shouldBe 201
        first.contentAsString shouldContain "\"date\":\"2026-09-14\""
        intendedAtOf(first) shouldBe Instant.parse("2026-09-14T22:59:59.999999Z")
        jdbc.queryForObject(
            "SELECT e.intended_at >= d.starts_at AND e.intended_at < d.ends_at FROM entries e JOIN bond_days d ON d.id = e.bond_day_id",
            Boolean::class.java,
        ) shouldBe true
        val replay = submit(ada, bondId, body, key)
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        replay.contentAsString shouldBe first.contentAsString
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
     * FR-041 input is a `422`, never a `500`: Postgres `text` cannot hold
     * U+0000, so a body carrying one used to pass every check and fail at the
     * insert. The JSON below spells it as the six-character escape, which is
     * how a well-formed client would send it.
     */
    @Test
    fun `an entry containing the NUL character is 422 naming the field, and never a 500`() {
        val response = submit(ada, bondId, """{"text":"thank you\u0000"}""")

        response.status shouldBe 422
        response.contentAsString shouldContain "\"field\":\"text\""
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 0
    }

    /**
     * Ruling P12 (spec §3.2, doc 04 §7): an entry is stored exactly as its
     * author sent it. NFKC rewrites every one of these — `…` to three full
     * stops, `²` to `2`, `™` to `TM`, `ﬁ` to `fi` — and a trim would take the
     * spaces either side. Followed through the whole path: the `201`, the
     * row, and `GET /today`.
     */
    @Test
    fun `an entry's words survive POST, the row and GET today byte for byte`() {
        val written = "  wait\u2026 x\u00b2, Moyi\u2122, \ufb01ne  "

        val created = submit(ada, bondId, """{"text":"$written"}""")

        created.status shouldBe 201
        created.getContentAsString(Charsets.UTF_8) shouldContain "\"text\":\"$written\""
        jdbc.queryForObject("SELECT text FROM entries", String::class.java) shouldBe written
        jdbc.queryForObject("SELECT encode(convert_to(text, 'UTF8'), 'hex') FROM entries", String::class.java) shouldBe
            written.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val today =
            mockMvc
                .get("/api/v1/bonds/$bondId/today") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(ada).token}") }
                .andReturn()
                .response
        today.status shouldBe 200
        today.getContentAsString(Charsets.UTF_8) shouldContain "\"text\":\"$written\""
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
     * Also proves the reservation is scoped to `(user_id, idempotency_key)`
     * (V11's own unique constraint), not to the key alone: two different
     * real callers reuse the identical literal key without colliding.
     */
    @Test
    fun `a real bearer token identity scopes Idempotency-Key, and two callers may share one key`() {
        val key = UUID.randomUUID().toString()

        val first = submit(ada, bondId, """{"text":"only once"}""", key)
        first.status shouldBe 201

        val replay = submit(ada, bondId, """{"text":"only once"}""", key)
        replay.status shouldBe 201
        // The entry re-read by the id the key recorded — V11 stores no text.
        replay.contentAsString shouldBe first.contentAsString
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 1

        val beasTurn = submit(bea, bondId, """{"text":"me too"}""", key)
        beasTurn.status shouldBe 201
        beasTurn.getHeader(IdempotencyInterceptor.REPLAYED_HEADER).shouldBeNull()
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 2
    }

    @Test
    fun `the idempotency row names the entry and holds none of its words in any column`() {
        val key = UUID.randomUUID().toString()
        submit(ada, bondId, """{"text":"a very particular gratitude xyzzy"}""", key).status shouldBe 201

        val row = jdbc.queryForMap("SELECT * FROM idempotency_keys")
        row.values.forEach { (it?.toString() ?: "") shouldNotContain "xyzzy" }
        row["result_kind"] shouldBe "ENTRY"
        row["result_id"].toString() shouldBe jdbc.queryForObject("SELECT id::text FROM entries", String::class.java)
        row["path"] shouldBe "/api/v1/bonds/$bondId/entries"
        row["method"] shouldBe "POST"
    }

    @Test
    fun `one key against a second bond's entries is refused, not answered from the first bond`() {
        val key = UUID.randomUUID().toString()
        val other = bondIdOf(createBond(ada))
        submit(ada, bondId, """{"text":"here"}""", key).status shouldBe 201

        val reused = submit(ada, other, """{"text":"here"}""", key)

        reused.status shouldBe 422
        reused.contentAsString shouldContain "\"code\":\"IDEMPOTENCY_KEY_REUSED\""
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

    private fun intendedAtOf(response: MockHttpServletResponse): Instant =
        Instant.parse(Regex(""""intendedAt":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1])

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

        /** Two days before [NOW] — see [setUp]. */
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
    }
}
