package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.ProcessedPhotos
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `POST /bonds/{bondId}/members/other/report` (FR-100's member half, ADR-0053 as amended for #127): a member reports
 * the other member of their bond to a person. Who may, the one `404`, what is stored, and that **nothing the reported
 * member can read changes** (doc 26 §2.2). What the operator then does with it is `ReportCommandsTest`'s.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryBondLockTest.TimeConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
internal class MemberReportsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val photos = ProcessedPhotos(jdbc)
    private val json = JsonMapper.builder().build()

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        clock.set(NOW.minusSeconds(86_400))
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        bondId = pair(ada, bea)
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, media_report_holds, media_moderation_log, media_assets")
        jdbc.execute("TRUNCATE TABLE entry_report_log, entry_reports")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a member reports the other member - 201 with the id and reason, stored as a member report, holding nothing`(
        output: CapturedOutput,
    ) {
        revealedWithPhoto()
        val outboxBefore = count("outbox_events")

        val reported = report(ada, bondId, """{"reason":"HARASSMENT","detail":"$DETAIL"}""")

        reported.status shouldBe 201
        val body = body(reported)
        body.propertyNames().asSequence().toList() shouldBe listOf("id", "reason")
        body["reason"].asString() shouldBe "HARASSMENT"
        val id = UUID.fromString(body["id"].asString())
        jdbc.queryForObject(
            "SELECT target || '|' || coalesce(entry_id::text, '-') || '|' || reported_member_id || '|' || " +
                "reporter_member_id || '|' || bond_id || '|' || status || '|' || detail FROM entry_reports WHERE id = ?",
            String::class.java,
            id,
        ) shouldBe "MEMBER|-|${memberId(bea)}|${memberId(ada)}|$bondId|OPEN|$DETAIL"
        // A member report holds no media (ADR-0053 decision 4 is the entry's), and publishes nothing: no push, no event.
        count("media_report_holds") shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE status <> 'READY'", Int::class.java) shouldBe 0
        count("outbox_events") shouldBe outboxBefore
        output.all shouldNotContain DETAIL
    }

    /** Doc 26 §2.2: no notification, ever, and nothing the reported member can read moves: not a byte, not an ETag. */
    @Test
    fun `nothing the reported member reads changes - their bond, their today and its ETag`() {
        revealedWithPhoto()
        val bondBefore = getBond(bea)
        val todayBefore = getToday(bea)
        val daysBefore = getDays(bea)

        report(ada, bondId, """{"reason":"HARASSMENT"}""").status shouldBe 201

        val bondAfter = getBond(bea)
        val todayAfter = getToday(bea)
        val daysAfter = getDays(bea)
        bondAfter.contentAsString shouldBe bondBefore.contentAsString
        bondAfter.getHeader(HttpHeaders.ETAG) shouldBe bondBefore.getHeader(HttpHeaders.ETAG)
        todayAfter.contentAsString shouldBe todayBefore.contentAsString
        todayAfter.getHeader(HttpHeaders.ETAG) shouldBe todayBefore.getHeader(HttpHeaders.ETAG)
        daysAfter.contentAsString shouldBe daysBefore.contentAsString
        daysAfter.getHeader(HttpHeaders.ETAG) shouldBe daysBefore.getHeader(HttpHeaders.ETAG)
        // The entry route holds the reported entry's photo back; this one holds nothing, so the reporter's view is unchanged too.
        body(getToday(ada))["partnerEntry"]["image"]["status"].asString() shouldBe "READY"
    }

    @Test
    fun `the same key is the same report, filed once, and replayed`() {
        val key = UUID.randomUUID().toString()

        val first = report(ada, bondId, """{"reason":"OTHER"}""", key)
        val again = report(ada, bondId, """{"reason":"OTHER"}""", key)

        first.status shouldBe 201
        again.status shouldBe 201
        first.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe null
        again.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        again.contentAsString shouldBe first.contentAsString
        count("entry_reports") shouldBe 1
        report(ada, bondId, """{"reason":"HARASSMENT"}""", key).status shouldBe 422
    }

    /**
     * The replay is authorised as a read of the report: a key that names a report the caller is not the reporter of
     * is the bond's one `404`, byte for byte a stranger's. Reached here by moving the stored report to the other
     * member, which no request can do; the rule is what is tested.
     */
    @Test
    fun `a replay of a report the caller did not make is the bond's one 404, the same bytes as a stranger's`() {
        val key = UUID.randomUUID().toString()
        val id = body(report(ada, bondId, """{"reason":"OTHER"}""", key))["id"].asString()
        jdbc.update(
            "UPDATE entry_reports SET reporter_member_id = reported_member_id, reported_member_id = reporter_member_id WHERE id = ?::uuid",
            id,
        )

        val replay = report(ada, bondId, """{"reason":"OTHER"}""", key)
        val stranger = report(users.verified("Eve"), bondId, """{"reason":"OTHER"}""")

        replay.status shouldBe 404
        replay.contentAsString.withoutInstance() shouldBe stranger.contentAsString.withoutInstance()
        replay.contentAsString shouldNotContain id
    }

    @Test
    fun `without an Idempotency-Key it is refused, and nothing is filed`() {
        val refused =
            mockMvc
                .post("/api/v1/bonds/$bondId/members/other/report") {
                    header(HttpHeaders.AUTHORIZATION, bearer(ada))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"reason":"OTHER"}"""
                }.andReturn()
                .response

        refused.status shouldBe 422
        refused.contentAsString shouldContain "Idempotency-Key"
        count("entry_reports") shouldBe 0
    }

    /** One answer for a bond the caller is not in and for one that never had a second member: the same bytes. */
    @Test
    fun `a stranger, an unknown bond, not an id, and a bond with nobody to report are the one 404`() {
        val eve = users.verified("Eve")
        val alone = createBond(eve)

        val answers =
            listOf(
                report(eve, bondId, """{"reason":"OTHER"}"""),
                report(eve, UUID.randomUUID().toString(), """{"reason":"OTHER"}"""),
                report(eve, "not-a-bond", """{"reason":"OTHER"}"""),
                report(eve, alone, """{"reason":"OTHER"}"""),
            )

        answers.forEach {
            it.status shouldBe 404
            it.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
        }
        answers.map { it.contentAsString.withoutInstance() }.distinct().size shouldBe 1
        count("entry_reports") shouldBe 0
    }

    @Test
    fun `a body that is not a report is 422 before the bond is looked at, the same to a stranger`() {
        val eve = users.verified("Eve")

        for (caller in listOf(ada, eve)) {
            for (bad in listOf("{}", """{"reason":"harassment"}""", """{"reason":"SPAM"}""")) {
                report(caller, bondId, bad).let {
                    it.status shouldBe 422
                    it.contentAsString shouldContain "\"field\":\"reason\""
                }
            }
            report(caller, bondId, """{"reason":"OTHER","detail":"${"x".repeat(501)}"}""").let {
                it.status shouldBe 422
                it.contentAsString shouldContain "\"field\":\"detail\""
            }
        }
        count("entry_reports") shouldBe 0
    }

    /** T-09: blocking is what a frightened person reaches for first; it must not take the report away. Nor leaving. */
    @Test
    fun `allowed after the reporter blocked, after the partner left, and to either member`() {
        end(ada, "block", """{"withdrawEntries":false}""")

        report(ada, bondId, """{"reason":"HARASSMENT"}""").status shouldBe 201
        // The blocked member can report too: what a report is never depends on who ended the bond.
        report(bea, bondId, """{"reason":"OTHER"}""").status shouldBe 201

        val cid = users.verified("Cid")
        val dee = users.verified("Dee")
        clock.set(NOW.minusSeconds(3_600))
        val other = pair(cid, dee)
        clock.set(NOW)
        mockMvc
            .post("/api/v1/bonds/$other/leave") { header(HttpHeaders.AUTHORIZATION, bearer(dee)) }
            .andReturn()
            .response.status shouldBe 204
        report(cid, other, """{"reason":"VIOLENCE_OR_SELF_HARM"}""").status shouldBe 201

        jdbc.queryForList("SELECT reason FROM entry_reports ORDER BY created_at, id", String::class.java).size shouldBe 3
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    private fun revealedWithPhoto() {
        val photo = photos.ready(owner = bea, verdict = "PASSED")
        submit(bea, """{"text":"a photo of us","imageMediaId":"$photo"}""").status shouldBe 201
        submit(ada, """{"text":"and mine"}""").status shouldBe 201
    }

    private fun memberId(user: UUID): UUID =
        jdbc.queryForObject(
            "SELECT id FROM bond_members WHERE bond_id = ?::uuid AND user_id = ?",
            UUID::class.java,
            bondId,
            user,
        )!!

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun report(
        caller: UUID,
        bond: String,
        body: String,
        key: String = UUID.randomUUID().toString(),
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bond/members/other/report") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun createBond(creator: UUID): String {
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(creator))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
        created.status shouldBe 201
        return Regex(""""id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
    }

    private fun pair(
        creator: UUID,
        joiner: UUID,
    ): String {
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(creator))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
        val code = Regex(""""code":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(joiner)) }
            .andReturn()
            .response.status shouldBe 200
        return Regex(""""id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
    }

    private fun submit(
        caller: UUID,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun end(
        caller: UUID,
        how: String,
        body: String,
    ) {
        mockMvc
            .post("/api/v1/bonds/$bondId/$how") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response.status shouldBe 204
    }

    private fun getBond(caller: UUID): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }
            .andReturn()
            .response
            .also { it.status shouldBe 200 }

    private fun getToday(caller: UUID): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId/today") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }
            .andReturn()
            .response
            .also { it.status shouldBe 200 }

    private fun getDays(caller: UUID): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId/days") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }
            .andReturn()
            .response
            .also { it.status shouldBe 200 }

    private fun bearer(user: UUID) = "Bearer ${tokens.issue(user).token}"

    private fun body(response: MockHttpServletResponse): JsonNode = json.readTree(response.contentAsString)

    private fun String.withoutInstance(): String = replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        /** The reporter's words: plainly a test's, and looked for in the log. */
        const val DETAIL = "fake-member-report-detail-never-logged"
    }
}
