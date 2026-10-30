package com.moyi.gratitude.web

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.ProcessedPhotos
import com.moyi.gratitude.infra.ProcessedVoices
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContainExactly
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `POST /entries/{entryId}/report` (FR-100, ADR-0053): who may report what,
 * what the report holds back, and what each of the two members is shown
 * afterwards. The media are put in place by hand, as `media.process` and
 * moderation leave them ([ProcessedPhotos], [ProcessedVoices]); what the
 * operator then decides is `ReportCommandsTest`'s.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryBondLockTest.TimeConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
internal class ReportsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val photos = ProcessedPhotos(jdbc)
    private val voices = ProcessedVoices(jdbc)
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

    /**
     * The owner's ruling Q16 ("a report path to human review and takedown"),
     * and the decision left to the owner (ADR-0053 decision 4): what was
     * reported is held back from the reporter at once. Both members are then
     * shown exactly what ADR-0051 shows of anything held back, and the author
     * nothing more.
     */
    @Test
    fun `a report holds the entry's photo and voice note back - processing to the reporter, not shared to the author`(
        output: CapturedOutput,
    ) {
        val (entry, photo, voice) = revealedWithMedia()
        partnerImage()["status"].asString() shouldBe "READY"
        val authorBefore = getToday(bea).contentAsString

        val reported = report(ada, entry, """{"reason":"NON_CONSENSUAL","detail":"$DETAIL"}""")

        reported.status shouldBe 201
        val body = body(reported)
        body.propertyNames().asSequence().toList() shouldBe listOf("id", "entryId", "reason")
        body["entryId"].asString() shouldBe entry
        body["reason"].asString() shouldBe "NON_CONSENSUAL"
        val reportId = UUID.fromString(body["id"].asString())
        // The reporter: processing, no URL, as the partner is shown anything held back; the words stay.
        val partners = body(getToday(ada))["partnerEntry"]
        partners["text"].asString() shouldBe "a photo of us"
        partners["image"]["status"].asString() shouldBe "PROCESSING"
        partners["image"]["url"].isNull shouldBe true
        partners["voice"]["status"].asString() shouldBe "PROCESSING"
        partners["voice"]["url"].isNull shouldBe true
        // The author: not shared, still with the photo (ADR-0051 decision 4), and nothing else changes.
        val authorAfter = getToday(bea).contentAsString
        val authors = body(authorAfter)["myEntry"]
        authors["image"]["status"].asString() shouldBe "NOT_SHARED"
        authors["image"]["url"].asString() shouldContain "X-Amz-Signature="
        authors["voice"]["status"].asString() shouldBe "NOT_SHARED"
        authorAfter shouldNotContain reportId.toString()
        authorAfter shouldNotContain "report"
        authorAfter.withoutMedia() shouldBe authorBefore.withoutMedia()
        // Recorded, held for this report, and logged as a member's report, never which member.
        jdbc.queryForObject(
            "SELECT status || '|' || reason || '|' || detail FROM entry_reports WHERE id = ?",
            String::class.java,
            reportId,
        ) shouldBe "OPEN|NON_CONSENSUAL|$DETAIL"
        holds(reportId) shouldContainExactly listOf(photo, voice).sorted()
        assetStatus(photo) shouldBe "QUARANTINED|QUARANTINED|REPORTED"
        jdbc.queryForList(
            "SELECT actor || '|' || note FROM media_moderation_log WHERE media_id = ?::uuid",
            String::class.java,
            photo,
        ) shouldBe
            listOf("member-report|report $reportId")
        // The reporter's words reach no log.
        output.all shouldNotContain DETAIL
    }

    @Test
    fun `the same key is the same report, filed once, and answered again as a replay`() {
        val (entry) = revealedWithMedia()
        val key = UUID.randomUUID().toString()

        val first = report(ada, entry, """{"reason":"HARASSMENT"}""", key)
        val again = report(ada, entry, """{"reason":"HARASSMENT"}""", key)

        first.status shouldBe 201
        again.status shouldBe 201
        again.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        first.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe null
        again.contentAsString shouldBe first.contentAsString
        count("entry_reports") shouldBe 1
        // The same key with another body is not a replay.
        report(ada, entry, """{"reason":"OTHER"}""", key).status shouldBe 422
    }

    @Test
    fun `a report without an Idempotency-Key is refused, and nothing is held`() {
        val (entry, photo) = revealedWithMedia()

        val refused =
            mockMvc
                .post("/api/v1/entries/$entry/report") {
                    header(HttpHeaders.AUTHORIZATION, bearer(ada))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"reason":"OTHER"}"""
                }.andReturn()
                .response

        refused.status shouldBe 422
        refused.contentAsString shouldContain "Idempotency-Key"
        count("entry_reports") shouldBe 0
        assetStatus(photo) shouldBe "READY|PASSED|"
    }

    @Test
    fun `one's own entry cannot be reported, revealed or not, and nothing is held`() {
        val photo = passedPhoto(owner = bea)
        val mine = body(submit(bea, """{"text":"mine","imageMediaId":"$photo"}"""))["id"].asString()

        val unrevealed = report(bea, mine, """{"reason":"OTHER"}""")
        submit(ada, """{"text":"and mine"}""").status shouldBe 201
        val revealed = report(bea, mine, """{"reason":"OTHER"}""")

        listOf(unrevealed, revealed).forEach {
            it.status shouldBe 409
            it.contentAsString shouldContain "\"code\":\"REPORT_ON_OWN_ENTRY\""
        }
        count("entry_reports") shouldBe 0
        assetStatus(photo) shouldBe "READY|PASSED|"
        // Its tombstone is still the author's own.
        delete(bea, mine).status shouldBe 204
        report(bea, mine, """{"reason":"OTHER"}""").let {
            it.status shouldBe 409
            it.contentAsString shouldContain "\"code\":\"REPORT_ON_OWN_ENTRY\""
        }
        count("entry_reports") shouldBe 0
    }

    /** 404, never 403, for everything the caller was never shown: the same bytes for every cause. */
    @Test
    fun `an entry the caller was never shown is the one 404 - locked, another bond's, unknown, not an id`() {
        val photo = passedPhoto(owner = bea)
        val locked = body(submit(bea, """{"text":"not yet","imageMediaId":"$photo"}"""))["id"].asString()
        val eve = users.verified("Eve")

        val answers =
            listOf(
                report(ada, locked, """{"reason":"OTHER"}"""),
                report(eve, locked, """{"reason":"OTHER"}"""),
                report(ada, UUID.randomUUID().toString(), """{"reason":"OTHER"}"""),
                report(ada, "not-an-id", """{"reason":"OTHER"}"""),
            )

        answers.forEach {
            it.status shouldBe 404
            it.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
        }
        answers.map { it.contentAsString.withoutInstance() }.distinct().size shouldBe 1
        count("entry_reports") shouldBe 0
        assetStatus(photo) shouldBe "READY|PASSED|"
    }

    @Test
    fun `a body that is not a report is refused before the entry is looked at, the same to a stranger`() {
        val (entry) = revealedWithMedia()
        val eve = users.verified("Eve")
        val tooLong = "x".repeat(501)

        for (caller in listOf(ada, eve)) {
            for (bad in listOf("{}", """{"reason":null}""", """{"reason":"other"}""", """{"reason":"SPAM"}""")) {
                report(caller, entry, bad).let {
                    it.status shouldBe 422
                    it.contentAsString shouldContain "\"field\":\"reason\""
                }
            }
            for (bad in listOf("", "   ", tooLong)) {
                report(caller, entry, """{"reason":"OTHER","detail":"$bad"}""").let {
                    it.status shouldBe 422
                    it.contentAsString shouldContain "\"field\":\"detail\""
                    it.contentAsString shouldNotContain tooLong
                }
            }
        }
        // Five hundred is allowed, counted in characters, not UTF-16 units: an emoji is one.
        report(ada, entry, """{"reason":"OTHER","detail":"${"🌻".repeat(500)}"}""").status shouldBe 201
        count("entry_reports") shouldBe 1
    }

    /** Q16: a person decides. Held back and marked for priority review, never blocked by the route. */
    @Test
    fun `sexual content involving a minor is held back like any report and never blocked by the route`() {
        val (entry, photo) = revealedWithMedia()

        report(ada, entry, """{"reason":"MINOR_SEXUAL_CONTENT"}""").status shouldBe 201

        assetStatus(photo) shouldBe "QUARANTINED|QUARANTINED|REPORTED"
        jdbc.queryForObject("SELECT evidence_held FROM media_assets WHERE id = ?::uuid", Boolean::class.java, photo) shouldBe false
    }

    @Test
    fun `an erased entry can still be reported, and what it showed is held until purged`() {
        val (entry, photo) = revealedWithMedia()
        delete(bea, entry).status shouldBe 204

        val reported = report(ada, entry, """{"reason":"NON_CONSENSUAL"}""")

        reported.status shouldBe 201
        // Deleted to everyone, its objects not yet purged (this context's storage answers nothing): held for the decision.
        holds(UUID.fromString(body(reported)["id"].asString())).size shouldBe 2
        assetStatus(photo).substringBefore('|') shouldBe "DELETED"
    }

    /**
     * T-09 (doc 09 §2): the member who blocked their partner, and kept their
     * own entries, reports what the partner showed them. Blocking is the
     * remedy a frightened person reaches for first; it must not take away the
     * report.
     */
    @Test
    fun `the member who blocked their partner can still report what they were shown`() {
        val (entry, photo) = revealedWithMedia()
        end(ada, "block", """{"withdrawEntries":false}""")

        report(ada, entry, """{"reason":"HARASSMENT"}""").status shouldBe 201

        assetStatus(photo) shouldBe "QUARANTINED|QUARANTINED|REPORTED"
    }

    /**
     * Review of #118, P1: deletion stays the author's right, and the reported
     * media's objects stay until a person has decided. After the author's
     * delete the hold is still there, and the operator can still block what
     * was reported, rather than find it GONE.
     */
    @Test
    fun `the author's delete after a report keeps the media held, and it can still be blocked`() {
        val (entry, photo, voice) = revealedWithMedia()
        val reportId = UUID.fromString(body(report(ada, entry, """{"reason":"MINOR_SEXUAL_CONTENT"}"""))["id"].asString())

        delete(bea, entry).status shouldBe 204

        assetStatus(photo).substringBefore('|') shouldBe "DELETED"
        holds(reportId) shouldContainExactly listOf(photo, voice).sorted()
        jdbc.queryForList(
            "SELECT outcome FROM entry_report_uphold(?, 'BLOCK', 'op-fake-ada', 'reported: FAKE-0003')",
            String::class.java,
            reportId,
        ) shouldBe listOf("BLOCKED", "BLOCKED")
        jdbc.queryForObject("SELECT evidence_held FROM media_assets WHERE id = ?::uuid", Boolean::class.java, photo) shouldBe true
    }

    /** Review of #118, P1: a withdrawal at the bond's end erases the author's entries; a report open on one keeps its media. */
    @Test
    fun `a withdrawal after a report keeps the media held`() {
        val (entry, photo) = revealedWithMedia()
        val reportId = UUID.fromString(body(report(ada, entry, """{"reason":"NON_CONSENSUAL"}"""))["id"].asString())

        end(bea, "block", """{"withdrawEntries":true}""")
        dispatcher.dispatchDue(clock.instant(), DISPATCH_BUDGET)

        assetStatus(photo).substringBefore('|') shouldBe "DELETED"
        holds(reportId).size shouldBe 2
    }

    /** Review of #118, P1: a report of a tombstone still finds what it showed, through the entry it was last on. */
    @Test
    fun `a report after a withdrawal holds the media its tombstone showed`() {
        val (entry, photo, voice) = revealedWithMedia()
        end(bea, "block", """{"withdrawEntries":true}""")
        dispatcher.dispatchDue(clock.instant(), DISPATCH_BUDGET)
        assetStatus(photo).substringBefore('|') shouldBe "DELETED"

        val reported = report(ada, entry, """{"reason":"NON_CONSENSUAL"}""")

        reported.status shouldBe 201
        holds(UUID.fromString(body(reported)["id"].asString())) shouldContainExactly listOf(photo, voice).sorted()
    }

    /** A report is never contact (doc 26 §2.2): a member who has left, or blocked, can still report what they were shown. */
    @Test
    fun `a report is accepted after the bond has ended`() {
        val (entry, photo) = revealedWithMedia()
        mockMvc
            .post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
            .andReturn()
            .response.status shouldBe 204

        report(ada, entry, """{"reason":"HARASSMENT"}""").status shouldBe 201

        assetStatus(photo) shouldBe "QUARANTINED|QUARANTINED|REPORTED"
    }

    /**
     * A photo moderation never reached is held too, and its absent verdict
     * remembered (V38), so a dismissal hands it back to moderation.
     */
    @Test
    fun `a photo not yet moderated is held, and remembers that it had no verdict`() {
        val photo = photos.ready(owner = bea)
        val entry = body(submit(bea, """{"text":"unmoderated","imageMediaId":"$photo"}"""))["id"].asString()
        submit(ada, """{"text":"and mine"}""").status shouldBe 201

        report(ada, entry, """{"reason":"OTHER"}""").status shouldBe 201

        assetStatus(photo) shouldBe "QUARANTINED|QUARANTINED|REPORTED"
        jdbc.queryForObject(
            "SELECT moderation_scores -> 'heldFrom' ->> 'verdict' IS NULL FROM media_assets WHERE id = ?",
            Boolean::class.java,
            photo,
        ) shouldBe true
    }

    /** Two reports of one entry each hold its media, one row each: dismissing one lets nothing go while the other is open. */
    @Test
    fun `two reports of one entry hold its media twice`() {
        val (entry, photo) = revealedWithMedia()

        val first = UUID.fromString(body(report(ada, entry, """{"reason":"OTHER"}"""))["id"].asString())
        val second = UUID.fromString(body(report(ada, entry, """{"reason":"HARASSMENT"}"""))["id"].asString())

        holds(first) shouldContainExactly holds(second)
        jdbc.queryForObject("SELECT count(*) FROM media_report_holds WHERE media_id = ?::uuid", Int::class.java, photo) shouldBe 2
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    /** Bea's revealed entry with a photo and a voice note that have passed moderation, and Ada's; Bea's id and the media's. */
    private fun revealedWithMedia(): List<String> {
        val photo = passedPhoto(owner = bea)
        val voice = voices.ready(owner = bea)
        jdbc.update("UPDATE media_assets SET moderation_verdict = 'PASSED', moderated_at = now() WHERE id = ?", voice)
        val entry =
            body(submit(bea, """{"text":"a photo of us","imageMediaId":"$photo","voiceMediaId":"$voice"}"""))["id"].asString()
        submit(ada, """{"text":"and mine"}""").status shouldBe 201
        return listOf(entry, photo.toString(), voice.toString())
    }

    private fun passedPhoto(owner: UUID): UUID = photos.ready(owner = owner, verdict = "PASSED")

    private fun partnerImage(): JsonNode = body(getToday(ada))["partnerEntry"]["image"]

    private fun holds(report: UUID): List<String> =
        jdbc
            .queryForList("SELECT media_id::text FROM media_report_holds WHERE report_id = ? ORDER BY media_id", String::class.java, report)
            .filterNotNull()

    private fun assetStatus(id: Any): String =
        jdbc.queryForObject(
            "SELECT status || '|' || coalesce(moderation_verdict, '') || '|' || coalesce(moderation_scores ->> 'reason', '') " +
                "FROM media_assets WHERE id = ?::uuid",
            String::class.java,
            id.toString(),
        )!!

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun report(
        caller: UUID,
        entryId: String,
        body: String,
        key: String = UUID.randomUUID().toString(),
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/entries/$entryId/report") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

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

    private fun delete(
        caller: UUID,
        entryId: String,
    ): MockHttpServletResponse =
        mockMvc.delete("/api/v1/entries/$entryId") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }.andReturn().response

    private fun getToday(caller: UUID): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds/$bondId/today") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }.andReturn().response

    private fun bearer(user: UUID) = "Bearer ${tokens.issue(user).token}"

    private fun body(response: MockHttpServletResponse): JsonNode = body(response.contentAsString)

    private fun body(text: String): JsonNode = json.readTree(text)

    private fun String.withoutInstance(): String = replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")

    /** The response with its two media objects taken out: what else the author is shown must not change. */
    private fun String.withoutMedia(): String =
        json
            .readTree(this)
            .let { tree ->
                (tree["myEntry"] as ObjectNode).remove(listOf("image", "voice"))
                tree.toString()
            }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        const val DISPATCH_BUDGET = 20

        /** The reporter's words: plainly a test's, and looked for in the log. */
        const val DETAIL = "fake-report-detail-never-logged"
    }
}
